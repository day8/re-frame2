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
  - The Xray public surface (rf2-jhn46) — curated subsets (direction 1
    only). spec/API.md tiers the Xray surfaces `internal-public` (the
    `mount` shell lifecycle + the `panels` `mount-<panel>!` family — the
    supported host-embed surface), `implementation` (the panel-leaf `Panel`
    reg-views, exported only so the shell composes them — NOT host-facing;
    rf2-oekz6s) and `tooling` (the `config` published constants, the
    `open-in-editor` chip, the `runtime` Xray↔MCP read seam), with the rest
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
      - `day8.re-frame2-xray.runtime` — the discovery sentinel + safe-egress
        entry points.
  - `re-frame.ui` (rf2-qz1h5d; rf2-vxgfnd.83 + rf2-vxgfnd.103) — the Spec-004
    compiled-view substrate. FULLY-ROWED (both directions): the ENTIRE blessed
    export set is rowed. That set is NOT re-listed here — it grows every stage
    and a second hand-maintained inventory would drift silently. The ONE
    authoritative enumeration is the JVM assertion
    `re-frame.ui.defview-grammar-jvm-test/export-surface-is-exactly-the-blessed-set`
    (`implementation/ui/test/re_frame/ui/defview_grammar_jvm_test.clj`), which
    pins `(ns-publics 're-frame.ui)` to an exact literal set; the manifest's
    `:cljs-only` `re-frame.ui` rows are its projection. A row removed / renamed
    in source → RED (direction 1); a NEW public Var added to this closed
    compiler grammar without a row → RED (direction 2), so an accidental
    public export cannot accumulate silently.

  - `re-frame.freehand` (rf2-drpa3.63) — the Spec-004 Freehand view
    substrate's ONE public door. Like `re-frame.ui.test` it is `.cljc` and
    JVM-introspected for its manifest rows (the generator owns tier/kind);
    this probe reconciles its live CLJS surface against those rows so
    neither host can silently expose an extra public. FULLY-ROWED, both
    directions — the descriptor type, its constructor and the emitter
    helpers live in the internal `re-frame.freehand.descriptor`, so a var
    that reappeared on the door would fail direction-2 completeness here.

  - `re-frame.freehand.test` (rf2-drpa3.79) — the Freehand substrate's
    STRUCTURAL TEST surface (`t`), enrolled on the same terms as the door and
    as `re-frame.ui.test`: `.cljc`, JVM-introspected for its manifest rows,
    its live CLJS surface reconciled here. FULLY-ROWED, both directions —
    `render` / `with-render` / `find` / `find-all` / `attrs` / `text` are the
    whole surface (everything beneath them is `defn-`), so one more public var
    added on either host, or one of the six renamed away, goes RED. Dev/test
    SCOPE is not privacy: this is a published authoring surface and the gate
    treats it like one.

  The pair-MCP server (`re-frame2-pair-mcp.server`) is the third
  CLJS-only surface the keystone names. It compiles under the pair-MCP
  artefact's OWN shadow-cljs build (`tools/re-frame2-pair-mcp`,
  `:server-test`) — it is not on the consolidated `:node-test`
  classpath and pulls the npm MCP SDK — so its probe rides that build;
  the shared `cljs-publics` + `cljs-probe` namespaces are the reusable
  mechanism for it (see rf2-2mtte PR notes)."
  (:require-macros [re-frame.api-manifest.cljs-publics
                    :refer [emit-ns-publics emit-cljs-only-rows
                            emit-classification-rows emit-ns-surface
                            emit-ui-test-signature-contract
                            emit-signature-contract]])
  (:require [cljs.test :refer-macros [deftest is testing]]
            [clojure.string :as str]
            [re-frame.api-manifest.cljs-probe :as probe]
            ;; The covered CLJS-only namespaces. The `:require` forces the
            ;; analyzer to analyse each before `emit-ns-publics` expands,
            ;; so the macro reads a real surface. The aliases are unused at
            ;; runtime (the surface is read at compile time) but the
            ;; requires are load-bearing.
            [re-frame.adapter.reagent]
            [re-frame.adapter.uix]
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
            [day8.re-frame2-xray.open-in-editor]
            [day8.re-frame2-xray.runtime]
            ;; rf2-qz1h5d — the Spec-004 compiled-view substrate. A `.cljc`
            ;; namespace already on the consolidated :node-test classpath
            ;; (ui/src); the require forces its analysis so `emit-ns-publics`
            ;; reads the live CLJS publics. Fully-rowed, BOTH directions
            ;; checked — see the Coverage note and `fully-rowed` below.
            [re-frame.ui]
            ;; rf2-vxgfnd.200 — the compiled-view substrate's testing surface.
            ;; UNLIKE re-frame.ui (curated :cljs-only), `re-frame.ui.test`'s
            ;; Tier-1 surface runs headless on the JVM, so it is JVM-INTROSPECTED
            ;; (generator `jvm-namespaces` + :classification rows). This probe
            ;; reconciles its reader-conditional CLJS surface against those rows
            ;; (via `emit-classification-rows` below) so the CLJS host cannot
            ;; silently expose an extra public. Fully-rowed, BOTH directions.
            [re-frame.ui.test]
            ;; rf2-drpa3.63 — the Freehand view substrate's public door. A
            ;; `.cljc` namespace already on the consolidated :node-test
            ;; classpath (freehand/src); JVM-introspected for its manifest
            ;; rows, its CLJS surface reconciled here. Fully-rowed, BOTH
            ;; directions.
            [re-frame.freehand]
            ;; rf2-drpa3.79 — the Freehand structural TEST surface, enrolled on
            ;; the same terms as the door above and as re-frame.ui.test.
            [re-frame.freehand.test]))

;; ---------------------------------------------------------------------------
;; The live CLJS public surface, captured at compile time.
;; ---------------------------------------------------------------------------

(def live-publics
  "`{ns-string [[var-string kind-kw] ...]}` for every covered CLJS-only
   namespace, enumerated off the analyzer at compile time."
  {"re-frame.adapter.reagent"  (emit-ns-publics re-frame.adapter.reagent)
   "re-frame.adapter.uix"      (emit-ns-publics re-frame.adapter.uix)
   "day8.re-frame2-xray.mount" (emit-ns-publics day8.re-frame2-xray.mount)
   ;; The Xray public surface (rf2-jhn46) — curated subsets (direction 1).
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
   "day8.re-frame2-xray.open-in-editor"              (emit-ns-publics day8.re-frame2-xray.open-in-editor)
   "day8.re-frame2-xray.runtime"                     (emit-ns-publics day8.re-frame2-xray.runtime)
   ;; rf2-qz1h5d + rf2-vxgfnd.83 + rf2-vxgfnd.103 — re-frame.ui compiled-view
   ;; substrate. FULLY-ROWED: the entire blessed export set is rowed, so
   ;; re-frame.ui is in `fully-rowed` below (both directions checked). The
   ;; blessed set itself is enumerated ONCE, in
   ;; `export-surface-is-exactly-the-blessed-set` (see the ns docstring) —
   ;; never re-listed here.
   "re-frame.ui"                                     (emit-ns-publics re-frame.ui)
   ;; rf2-vxgfnd.200 — re-frame.ui.test testing surface. JVM-introspected for
   ;; its manifest rows (the generator owns tier/kind); the probe reconciles
   ;; the live CLJS surface so a CLJS-only public addition reddens too.
   "re-frame.ui.test"                                (emit-ns-publics re-frame.ui.test)
   ;; rf2-drpa3.63 — the Freehand public door. JVM-introspected for its
   ;; manifest rows; the probe reconciles the live CLJS surface so a
   ;; CLJS-only public addition reddens too.
   "re-frame.freehand"                               (emit-ns-publics re-frame.freehand)
   ;; rf2-drpa3.79 — the Freehand structural test surface. Same terms: JVM
   ;; -introspected rows, live CLJS surface reconciled here, fully-rowed.
   "re-frame.freehand.test"                          (emit-ns-publics re-frame.freehand.test)})

(def fully-rowed
  "Namespaces whose ENTIRE public surface must be rowed (direction 2).
   The two adapter namespaces — their public surface IS the documented
   adapter API — and `re-frame.ui`, the Spec-004 compiled-view substrate,
   whose entire blessed export set is rowed (that set is enumerated ONCE, in
   `export-surface-is-exactly-the-blessed-set` — see the ns docstring), so a
   NEW accidental public Var added to this first-party closed compiler grammar
   fails direction-2 completeness. The Xray mount surface stays a curated
   subset (direction 1 only), so it is deliberately absent here."
  #{"re-frame.adapter.reagent"
    "re-frame.adapter.uix"
    "re-frame.ui"
    ;; rf2-vxgfnd.200 — direction-2 completeness on the CLJS side: a NEW
    ;; public var added to re-frame.ui.test's CLJS surface without a manifest
    ;; row → RED. Its rows come from :classification (JVM lane), fed in via
    ;; `ui-test-rows` below, not :cljs-only.
    "re-frame.ui.test"
    ;; rf2-drpa3.63 — direction-2 completeness for the Freehand door: a NEW
    ;; public var added on either host without a manifest row → RED. Its rows
    ;; come from :classification (JVM lane), fed in via `freehand-rows` below.
    "re-frame.freehand"
    ;; rf2-drpa3.79 — direction-2 completeness for the Freehand structural
    ;; test surface: the rowed names ARE the surface, so one more public var
    ;; added on either host without a manifest row → RED. Its rows likewise
    ;; come from :classification, fed in via `freehand-test-rows` below.
    "re-frame.freehand.test"})

(def cljs-only-rows
  "The `:cljs-only` rows from spec/api-manifest-metadata.edn, embedded at
   compile time (no runtime filesystem)."
  (emit-cljs-only-rows))

(def ui-test-rows
  "The `re-frame.ui.test` `:classification` rows (rf2-vxgfnd.200), projected
   to `{:namespace :var}` so the probe reconciles them exactly like the
   `:cljs-only` rows. `re-frame.ui.test` is JVM-introspected for its manifest
   rows (its Tier-1 surface runs headless on the JVM), but its reader-
   conditional CLJS surface must still be reconciled here so neither host can
   silently expose an extra public."
  (emit-classification-rows "re-frame.ui.test"))

(def freehand-rows
  "The `re-frame.freehand` `:classification` rows (rf2-drpa3.63), projected
   exactly as `ui-test-rows` is. The Freehand door is JVM-introspected for
   its manifest rows, but its CLJS surface must still be reconciled here so
   neither host can silently expose an extra public — in particular the
   descriptor constructor, which lives in the internal
   `re-frame.freehand.descriptor` and must never reappear on the door."
  (emit-classification-rows "re-frame.freehand"))

(def freehand-test-rows
  "The `re-frame.freehand.test` `:classification` rows (rf2-drpa3.79),
   projected exactly as `freehand-rows` is. The Freehand structural test
   surface is JVM-introspected for its manifest rows, but its CLJS surface
   must still be reconciled here so neither host can silently expose an extra
   public — a test-authoring surface is a published surface."
  (emit-classification-rows "re-frame.freehand.test"))

(def reconcile-rows
  "Every row the probe reconciles: the `:cljs-only` surfaces plus the
   JVM-owned `re-frame.ui.test`, `re-frame.freehand` and
   `re-frame.freehand.test` rows."
  (-> cljs-only-rows
      (into ui-test-rows)
      (into freehand-rows)
      (into freehand-test-rows)))

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

;; ---------------------------------------------------------------------------
;; re-frame.freehand.test ENROLMENT — the focused negative control (rf2-drpa3.79).
;;
;; The structural test surface shipped as a public authoring surface
;; and was invisible to every public-API inventory: the generator did not
;; enumerate it, the probe did not reconcile it, so `gen --check` and this
;; probe were both green while a rename, a removal or an accidental export in
;; that namespace moved nothing. Enrolment is only worth having if it BITES,
;; so the three tests below drive the live rows + live surface through the same
;; pure reconciler the gate above uses: green as it stands, RED when a public
;; var is added, RED when one is removed, green again on restore.
;; ---------------------------------------------------------------------------

(def ^:private freehand-test-ns "re-frame.freehand.test")

(def ^:private freehand-test-names
  "The six blessed names. Spelled once, here, as the non-vacuity anchor — the
   five queries `render` / `find` / `find-all` / `attrs` / `text` plus the
   `with-render` bracket that opens the discardable render a state-reading
   view needs (Spec 008)."
  #{"render" "with-render" "find" "find-all" "attrs" "text"})

(deftest freehand-test-surface-is-exactly-the-six-names
  (testing "non-vacuity: the probe really analysed the namespace, the sidecar
            really carries its rows, and the two are the same six names — so a
            green below cannot mean 'nothing was compared'"
    (is (= freehand-test-names
           (set (map first (get live-publics freehand-test-ns))))
        "the live CLJS surface is exactly the six blessed names")
    (is (= freehand-test-names (set (map :var freehand-test-rows)))
        "and the sidecar :classification rows are exactly the same six")))

(deftest freehand-test-added-public-var-goes-red
  (testing "DIRECTION 2: one more public var on the structural test surface with
            no manifest row fails completeness — the drift an unenrolled
            namespace made invisible"
    (let [result (probe/reconcile
                  (update live-publics freehand-test-ns conj ["click!" :fn])
                  reconcile-rows
                  fully-rowed)]
      (is (not (probe/in-sync? result)))
      (is (= [{:namespace freehand-test-ns :var "click!" :live-kind :fn}]
             (:missing result))
          "the report NAMES the unrowed var")
      (is (empty? (:stale result))
          "and flags nothing else — the negative control is focused"))))

(deftest freehand-test-removed-public-var-goes-red
  (testing "DIRECTION 1: a rowed name the live surface no longer exposes (a
            rename or a removal) is stale — the other half of the guard"
    (let [result (probe/reconcile
                  (update live-publics freehand-test-ns
                          (fn [pairs] (vec (remove #(= "render" (first %)) pairs))))
                  reconcile-rows
                  fully-rowed)]
      (is (not (probe/in-sync? result)))
      (is (= [{:namespace freehand-test-ns :var "render"}] (:stale result))
          "the report NAMES the vanished var")
      (is (empty? (:missing result))))))

(deftest freehand-test-restored-surface-is-green
  (testing "POSITIVE CONTROL: the UNMUTATED live surface reconciles clean, so
            the two reds above are the mutation talking and not a standing
            failure"
    (is (probe/in-sync? (probe/reconcile live-publics reconcile-rows fully-rowed)))))

;; ---------------------------------------------------------------------------
;; re-frame.ui.test HOST-ARITY guard — CLJS (:cljs) lane (rf2-5bcdi).
;;
;; The manifest carries name + :kind but NO arity, so a re-frame.ui.test
;; FUNCTION can reshape a supported arity and stay green — and the contract is
;; host-specific: `flush!` is 0-arity on the JVM but 0/1-arity on CLJS. This
;; enumerates the live CLJS analyzer arities of the reader-conditional surface
;; and reconciles the FUNCTION arities against the `:cljs` half of the sidecar
;; signature authority. A CLJS-only reshape (e.g. dropping flush!'s 1-arity)
;; turns this RED. Macros are host-invariant (pinned once on the JVM lane).
;; ---------------------------------------------------------------------------

(def live-ui-test-surface
  "`{var-name {:kind kw :arities (#{arity} | nil)}}` for re-frame.ui.test's live
   CLJS public surface, enumerated off the analyzer at compile time — the live
   classification (kind) + host arities the sidecar signature contract is
   reconciled against (rf2-d7sso)."
  (emit-ns-surface re-frame.ui.test))

(def ui-test-signature-contract
  "The sidecar `:ui-test-signatures` authority, embedded at compile time
   (no runtime filesystem). `:vars` is the per-var host-aware contract."
  (emit-ui-test-signature-contract))

(deftest ui-test-cljs-signatures-in-sync
  (testing "the live CLJS classification + function arities of re-frame.ui.test
            match the signature contract (kind reconciled; flush! 0/1-arity pinned)"
    (let [problems (probe/signature-problems (:vars ui-test-signature-contract)
                                             live-ui-test-surface)]
      (is (empty? problems) (probe/signature-report problems)))))

(deftest ui-test-signature-contract-is-non-vacuous
  (testing "the embedded contract carries the SIX blessed ui.test vars (render /
            attrs / text on the JVM structural host, with-root / flush! /
            flush-presence! on the CLJS mounted host — the ratified rf2-n7jtp
            minimization) and the analyzer surfaced flush!'s live CLJS :fn
            0/1-arity (guards against a vacuous green from an unread sidecar or
            un-analysed namespace)"
    (is (= "re-frame.ui.test" (:namespace ui-test-signature-contract)))
    (is (= 6 (count (:vars ui-test-signature-contract))))
    (is (= :fn (get-in live-ui-test-surface ["flush!" :kind]))
        "flush! must be classified :fn by the live analyzer — the kind authority")
    (is (= #{[0] [1]} (get-in live-ui-test-surface ["flush!" :arities]))
        "flush! must be observed 0/1-arity on CLJS — the blessed host difference")))

;; Synthetic reconciler contracts — the mutation proof, exercised directly on
;; the pure `signature-problems` so a reshape / kind flip goes RED and the
;; in-sync state stays green regardless of the live analyzer.
;; Carries BOTH halves, exactly like a real sidecar row: `flush!` keeps the
;; blessed host-specific function difference (0-arity JVM, 0/1-arity CLJS),
;; and the two macros are host-invariant (`:clj` = `:cljs`).
(def ^:private synthetic-contract
  {"attrs"     {:kind :fn    :clj #{[1]}     :cljs #{[1]}}
   "flush!"    {:kind :fn    :clj #{[0]}     :cljs #{[0] [1]}}
   "render"    {:kind :macro :clj #{[1] [2]} :cljs #{[1] [2]}}
   "with-root" {:kind :macro :clj #{[1 :&]}  :cljs #{[1 :&]}}})

;; The live SURFACE fixture: {var {:kind :arities}}. A macro's analyzer arities
;; may be nil (host-invariant, never arity-checked here).
(def ^:private synthetic-live-in-sync
  {"attrs"     {:kind :fn    :arities #{[1]}}
   "flush!"    {:kind :fn    :arities #{[0] [1]}}
   "render"    {:kind :macro :arities #{[1] [2]}}
   "with-root" {:kind :macro :arities #{[1 :&]}}})

(deftest cljs-arities-in-sync-produce-no-problems
  (testing "the matching live CLJS surface reconciles clean"
    (is (empty? (probe/signature-problems synthetic-contract synthetic-live-in-sync)))))

(deftest cljs-flush-arity-drop-goes-red
  (testing "THE BUG (rf2-5bcdi): flush! losing its CLJS 1-arity (thunk) fails
            against :cljs #{[0] [1]} — a CLJS-only reshape the manifest cannot see"
    (let [problems (probe/signature-problems
                    synthetic-contract
                    (assoc-in synthetic-live-in-sync ["flush!" :arities] #{[0]}))]
      (is (= [:arity-mismatch] (map :kind problems)))
      (is (= "flush!" (:var (first problems))))
      (is (= #{[0] [1]} (:expected (first problems))))
      (is (= #{[0]} (:got (first problems)))))))

(deftest cljs-added-arity-goes-red
  (testing "ADDING a CLJS-only arity (attrs gains a 2-arity) fails — a superset
            is drift, not a pass"
    (let [problems (probe/signature-problems
                    synthetic-contract
                    (assoc-in synthetic-live-in-sync ["attrs" :arities] #{[1] [2]}))]
      (is (= [:arity-mismatch] (map :kind problems)))
      (is (= "attrs" (:var (first problems)))))))

(deftest cljs-unobserved-function-goes-red
  (testing "a contracted FUNCTION the analyzer surfaces no arity for is
            :arity-unobserved (never a vacuous pass)"
    (let [problems (probe/signature-problems
                    synthetic-contract
                    (assoc-in synthetic-live-in-sync ["attrs" :arities] nil))]
      (is (= [:arity-unobserved] (map :kind problems)))
      (is (= "attrs" (:var (first problems)))))))

(deftest cljs-sidecar-kind-flip-goes-red
  (testing "THE rf2-d7sso BUG: a sidecar entry whose :kind was flipped :fn→:macro
            is REJECTED against the live analyzer kind (:fn) — the CLJS lane no
            longer silently SKIPS the entry on the unvalidated sidecar kind (its
            arities still match, so ONLY the kind mismatch fires)"
    (let [problems (probe/signature-problems
                    (assoc-in synthetic-contract ["flush!" :kind] :macro)
                    synthetic-live-in-sync)]
      (is (= [:kind-mismatch] (map :kind problems)))
      (is (= "flush!" (:var (first problems))))
      (is (= :macro (:declared (first problems))))
      (is (= :fn (:live-kind (first problems)))))))

(deftest cljs-classified-var-without-signature-goes-red
  (testing "a live blessed var with no :ui-test-signatures entry (an omitted
            contract entry, or a classified CLJS-only function added without a
            host-arity contract) is :uncontracted-var — cannot escape coverage"
    (let [problems (probe/signature-problems
                    (dissoc synthetic-contract "attrs")
                    synthetic-live-in-sync)]
      (is (= [:uncontracted-var] (map :kind problems)))
      (is (= "attrs" (:var (first problems)))))))

(deftest cljs-contract-var-not-live-goes-red
  (testing "a contract var the live CLJS surface no longer exposes is :var-absent"
    (let [problems (probe/signature-problems
                    synthetic-contract
                    (dissoc synthetic-live-in-sync "attrs"))]
      (is (= [:var-absent] (map :kind problems)))
      (is (= "attrs" (:var (first problems)))))))

(deftest cljs-macros-are-not-arity-checked
  (testing "macros (render / with-root) are host-invariant — the CLJS lane does
            NOT arity-flag them against the ANALYZER even when it surfaces no
            arity for them (their grammar is pinned on the JVM lane, against
            the live JVM :arglists), as long as they are present with the
            :macro classification"
    (is (empty? (probe/signature-problems
                 synthetic-contract
                 (-> synthetic-live-in-sync
                     (assoc-in ["render" :arities] nil)
                     (assoc-in ["with-root" :arities] nil)))))))

;; ---------------------------------------------------------------------------
;; MACRO HOST-INVARIANCE — the unchecked shadow contract (rf2-qw31o)
;;
;; A macro row's `:cljs` half used to be read by NOTHING: this lane skipped
;; macros outright and the JVM lane reads only `:clj`, so an arbitrary mutation
;; to `render`'s or `with-root`'s `:cljs` grammar stayed green on BOTH lanes.
;; Now both reconcilers require a macro's two halves to be equal. Neither
;; consults the analyzer for a macro arity — the check is sidecar-internal, and
;; `:clj` is what stays pinned to the live JVM `:arglists`.
;; ---------------------------------------------------------------------------

(deftest cljs-macro-cljs-only-mutation-goes-red
  (testing "THE BUG (rf2-qw31o): mutating render's grammar ONLY on the :cljs
            side is host-variance a single .cljc defmacro cannot have. It used
            to be invisible to every lane; it is now RED with an actionable
            diagnostic naming both halves"
    (let [problems (probe/signature-problems
                    (assoc-in synthetic-contract ["render" :cljs] #{[7] [9]})
                    synthetic-live-in-sync)]
      (is (= [:macro-host-variance] (map :kind problems)))
      (is (= "render" (:var (first problems))))
      (is (= #{[1] [2]} (:expected (first problems))) "the :clj half")
      (is (= #{[7] [9]} (:got (first problems)))      "the mutated :cljs half")
      (let [report (probe/signature-report problems)]
        (is (str/includes? report "render") "the report NAMES the offending var")
        (is (str/includes? report ":cljs equal to :clj")
            "and names the remedy — an actionable diagnostic, not a bare diff")))))

(deftest cljs-macro-clj-only-mutation-goes-red
  (testing "the OTHER direction: mutating with-root's :clj half alone is the
            same host-variance and is equally rejected here (the JVM lane
            additionally catches it against the live Var :arglists), so neither
            half can be edited on its own"
    (let [problems (probe/signature-problems
                    (assoc-in synthetic-contract ["with-root" :clj] #{[2 :&]})
                    synthetic-live-in-sync)]
      (is (= [:macro-host-variance] (map :kind problems)))
      (is (= "with-root" (:var (first problems)))))))

(deftest cljs-macro-grammar-changed-in-both-halves-stays-green
  (testing "POSITIVE CONTROL — over-tightening would be worse than the bug. A
            macro grammar that REALLY changed, changed in BOTH halves together,
            is in sync and must not be flagged. Without this, 'always flag a
            macro' would pass the two mutation tests above while making every
            legitimate grammar change unrepresentable"
    (is (empty? (probe/signature-problems
                 (-> synthetic-contract
                     (assoc-in ["render" :clj]  #{[1] [2] [3]})
                     (assoc-in ["render" :cljs] #{[1] [2] [3]}))
                 synthetic-live-in-sync)))))

(deftest cljs-function-host-difference-is-never-forced-equal
  (testing "POSITIVE CONTROL for the other half of the rule — host-invariance
            binds MACROS only. flush!'s contract is deliberately :clj #{[0]}
            and :cljs #{[0] [1]}, a real reader-conditional difference, and
            reconciles clean. A check that forced :clj = :cljs for every kind
            would redden this legitimate row"
    (is (not= (get-in synthetic-contract ["flush!" :clj])
              (get-in synthetic-contract ["flush!" :cljs]))
        "the fixture really does carry the host difference")
    (is (empty? (probe/signature-problems synthetic-contract
                                          synthetic-live-in-sync)))))

;; ---------------------------------------------------------------------------
;; re-frame.freehand.test HOST-SIGNATURE guard — CLJS (:cljs) lane (rf2-drpa3.99).
;;
;; The SIBLING of the re-frame.ui.test CLJS lane above, reusing the SAME pure
;; reconciler `probe/signature-problems`. The manifest + the enrolment probe
;; carry name + :kind but NO arity, so a freehand.test fn/macro could reshape a
;; supported arity and stay green (the reproduction: `render` grew a second
;; `[form opts]` arity while name/kind held and every gate stayed green — so
;; `(t/render form)` could silently break). This enumerates the live CLJS
;; analyzer surface of re-frame.freehand.test and reconciles the FUNCTION
;; arities against the `:cljs` half of the sidecar :freehand-test-signatures
;; authority; `with-render` is host-invariant (pinned on the JVM lane, its two
;; halves required equal). All six names are host-identical `.cljc` — no
;; CLJS-only (`:clj nil`) verb, unlike ui.test's flush verbs.
;; ---------------------------------------------------------------------------

(def live-freehand-test-surface
  "`{var-name {:kind kw :arities (#{arity} | nil)}}` for re-frame.freehand.test's
   live CLJS public surface, enumerated off the analyzer at compile time — the
   live classification (kind) + host arities the signature contract is
   reconciled against."
  (emit-ns-surface re-frame.freehand.test))

(def freehand-test-signature-contract
  "The sidecar `:freehand-test-signatures` authority, embedded at compile time
   (no runtime filesystem). `:vars` is the per-var host-aware contract."
  (emit-signature-contract :freehand-test-signatures))

(deftest freehand-test-cljs-signatures-in-sync
  (testing "the live CLJS classification + function arities of
            re-frame.freehand.test match the signature contract (kind
            reconciled; render/find/find-all/attrs/text arities pinned)"
    (let [problems (probe/signature-problems (:vars freehand-test-signature-contract)
                                             live-freehand-test-surface)]
      (is (empty? problems) (probe/signature-report problems)))))

(deftest freehand-test-signature-contract-is-non-vacuous
  (testing "the embedded contract carries the SIX blessed freehand.test vars and
            the analyzer surfaced render's live CLJS :fn 1-arity — guards against
            a vacuous green from an unread sidecar or un-analysed namespace"
    (is (= "re-frame.freehand.test" (:namespace freehand-test-signature-contract)))
    (is (= 6 (count (:vars freehand-test-signature-contract))))
    (is (= freehand-test-names (set (keys (:vars freehand-test-signature-contract))))
        "the signature authority names exactly the six blessed vars")
    (is (= :fn (get-in live-freehand-test-surface ["render" :kind]))
        "render must be classified :fn by the live analyzer — the kind authority")
    (is (= #{[1]} (get-in live-freehand-test-surface ["render" :arities]))
        "render must be observed 1-arity on CLJS — the contract the reproduction broke")
    (is (= :macro (get-in live-freehand-test-surface ["with-render" :kind]))
        "with-render must be classified :macro by the live analyzer")))

(deftest freehand-test-cljs-render-arity-reshape-goes-red
  (testing "THE REPRODUCTION (rf2-drpa3.99): render reshaped from 1-arity to
            2-arity ([form] → [form opts]) fails against the contract's
            :cljs #{[1]}, while its name + :kind (a :fn) are unchanged — the
            exact false-green every prior gate let through"
    (let [problems (probe/signature-problems
                    (:vars freehand-test-signature-contract)
                    (assoc-in live-freehand-test-surface ["render" :arities] #{[1] [2]}))]
      (is (= [:arity-mismatch] (map :kind problems)))
      (is (= "render" (:var (first problems))))
      (is (= #{[1]} (:expected (first problems))))
      (is (= #{[1] [2]} (:got (first problems)))))))

(deftest freehand-test-cljs-added-arity-goes-red
  (testing "ADDING a CLJS arity (text gains a 2-arity) fails — a superset is
            drift, not a pass"
    (let [problems (probe/signature-problems
                    (:vars freehand-test-signature-contract)
                    (assoc-in live-freehand-test-surface ["text" :arities] #{[1] [2]}))]
      (is (= [:arity-mismatch] (map :kind problems)))
      (is (= "text" (:var (first problems)))))))

(deftest freehand-test-cljs-with-render-host-variance-goes-red
  (testing "with-render is one .cljc defmacro, so its :clj and :cljs halves
            cannot differ — mutating the :cljs half alone is host-variance a
            single defmacro cannot have, and is RED (the JVM lane pins :clj to
            the live macro :arglists)"
    (let [problems (probe/signature-problems
                    (assoc-in (:vars freehand-test-signature-contract)
                              ["with-render" :cljs] #{[3 :&]})
                    live-freehand-test-surface)]
      (is (= [:macro-host-variance] (map :kind problems)))
      (is (= "with-render" (:var (first problems))))
      (is (= #{[0 :&]} (:expected (first problems))) "the :clj half")
      (is (= #{[3 :&]} (:got (first problems)))      "the mutated :cljs half"))))

(deftest freehand-test-cljs-sidecar-kind-flip-goes-red
  (testing "a sidecar entry whose :kind was flipped :fn→:macro is REJECTED
            against the live analyzer kind (:fn) — the declared kind is
            reconciled, never trusted to select checks"
    (let [problems (probe/signature-problems
                    (assoc-in (:vars freehand-test-signature-contract)
                              ["render" :kind] :macro)
                    live-freehand-test-surface)]
      (is (= [:kind-mismatch] (map :kind problems)))
      (is (= "render" (:var (first problems))))
      (is (= :macro (:declared (first problems))))
      (is (= :fn (:live-kind (first problems)))))))

(deftest freehand-test-cljs-restored-surface-is-green
  (testing "POSITIVE CONTROL: the UNMUTATED live surface reconciles clean, so
            the reds above are the mutation talking and not a standing failure"
    (is (empty? (probe/signature-problems (:vars freehand-test-signature-contract)
                                          live-freehand-test-surface)))))
