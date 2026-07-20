(ns re-frame.ui.parity-corpus-elision-prod-test
  "rf2-ckyfh, G-7 Layer 1 — the ADVANCED-CLJS half of the dev<->prod
  structural-equivalence triangle.

  `parity_corpus_cljs_test` already closes ONE leg: for every corpus case
  the DEV CLJS emitter's output (`:node-test`, `:none`, goog.DEBUG=true),
  rendered against real React via react-dom/server, is NORMALIZED-
  STRUCTURALLY-EQUIVALENT under normalization N to the JVM emitter's
  truth. That proves dev-CLJS == JVM-truth.

  This namespace closes the OTHER leg in the mode it is written for. It
  runs ONLY in `:browser-test-prod-elision` (`shadow-cljs release` =>
  `:optimizations :advanced`, goog.DEBUG=false), renders the SAME corpus
  through the ADVANCED CLJS emitter via react-dom/server, and compares in
  the SAME normalized-semantic space against the SAME JVM truth. That
  proves advanced-CLJS == JVM-truth.

  The two legs share one vertex — the JVM truth, embedded at CLJS COMPILE
  time by `re-frame.ui.parity-embed/embedded-jvm-semantics` (a Clojure
  macro whose expansion is identical at every optimization level, so the
  advanced build receives byte-for-byte the same truth the dev build
  does). dev-CLJS == JVM and advanced-CLJS == JVM therefore compose to
  dev-CLJS == advanced-CLJS per generated shape — with NO golden files
  and NO cross-build artifact protocol. A production-only structural
  divergence (the class rf2-vxgfnd.253 recorded a real wrapper-selection
  bug in) fails HERE while the dev leg stays green.

  What this DOES cover: the generated STRUCTURAL output of all 43 corpus
  cases (28 views, incl. the S4 presence / custom-element rows — for custom
  elements, the tag / children / non-property attrs; see the property-prop
  note by `render-case`). What it does NOT cover: mounted production-wrapper
  BEHAVIOUR (retention timers, event targets, sub/handle ownership, cleanup)
  and custom-element DOM property APPLICATION — Layer 2, owned by the S6 leaf
  rf2-55zsd (per-wrapper mounted causal fixtures).

  Three teeth guard against a silent pass:
    (i)   `debug-regime-is-off` asserts `interop/debug-enabled?` is false
          in-bundle, so the corpus comparison cannot silently run in the
          dev regime and pass vacuously.
    (ii)  `advanced-build-really-renames-unexterned-property-reads` is the
          non-vacuity control (the binding_plan pattern): it proves this
          exact bundle is genuinely `:advanced` with property renaming
          LIVE, so the corpus comparison is a real proof rather than a
          build that quietly stopped optimizing.
    (iii) a deliberate production-arm mutation (breaking the direct-memo /
          `memo-view` arm) was demonstrated during development to turn
          `every-case-normalized-structural-equivalence` RED here while
          the dev corpus stayed green — then restored (rf2-ckyfh)."
  (:require [cljs.test :refer-macros [deftest is testing]]
            ["react-dom/server" :as rds]
            [re-frame.interop :as interop]
            [re-frame.ui :refer [defview]]
            [re-frame.ui.parity-fixtures :as fx]
            [re-frame.ui.parity-html :as ph]
            [re-frame.ui.rules :as rules]
            [re-frame.ui.runtime :as rt])
  (:require-macros [re-frame.ui.parity-embed :refer [embedded-jvm-semantics]]))

;; The JVM emitter's normalized truth, computed in the compiler's OWN JVM at
;; CLJS compile time and embedded as literals — the SAME vertex the dev-lane
;; `parity_corpus_cljs_test` compares against. Its value does not depend on the
;; optimization level: the macro renders `.cljc` fixtures through the JVM emitter
;; regardless of whether the CLJS side is later compiled `:none` or `:advanced`.
(def jvm-semantics (embedded-jvm-semantics))

(defn- ->props
  "Corpus EDN props -> JS props object per the frozen props ABI
  (deterministic quoted slot names preserving namespace+name)."
  [m]
  (reduce-kv (fn [o k v]
               (unchecked-set o (subs (str k) 1) v)
               o)
             #js {} m))

(defn- render-case [{:keys [view props]}]
  (rds/renderToStaticMarkup (rt/jsx2 (get fx/views view) (->props props))))

;; --- Layer 1 / Layer 2 boundary for the custom-element rows -----------------
;; `parity_html/html->semantic` strips DECLARED custom-element property-props
;; from the React side via the RUNTIME registry (`rules/custom-element-properties`)
;; — React SSR serialises a custom-element property as a camelCase attribute
;; (`accentColor="…"`, `tabIndex="…"`), and the contract's semantic space omits
;; it because it is applied as a DOM property, not authored structure. In THIS
;; advanced bundle that runtime registry is EMPTY — the corpus's top-level
;; `ui/custom-element` registrations do not repopulate `custom-element-properties`
;; here (FINDING, rf2-ckyfh: worth a root-cause follow-up — the same registry
;; feeds production dynamic `ui/spread` custom-element classification). The
;; emitter OUTPUT is identical dev<->prod (React serialises the property-prop the
;; same way regardless of goog.DEBUG); only the harness's registry-driven strip
;; is unavailable. So Layer 1 strips those property-props here directly, from the
;; SAME compile-time classification the emitter and the JVM truth use, keeping the
;; comparison in one semantic space. Custom-element property APPLICATION is Layer
;; 2 (rf2-55zsd); Layer 1 proves the generated STRUCTURE (tag, children,
;; non-property attrs). Keep in sync with parity-fixtures' `ui/custom-element`
;; declarations (`:parity-widget` / `:parity-attrish`).
(def ^:private corpus-ce-property-attr-names
  {"parity-widget"  #{(rules/custom-element-property-name (name :accent-color))}  ;; "accentColor"
   "parity-attrish" #{(rules/custom-element-property-name (name :tab-index))}})   ;; "tabIndex"

(defn- strip-ce-property-props
  "Drop the corpus's declared custom-element property-prop attrs from a semantic
  vector/node — the Layer 1/Layer 2 boundary `parity_html` draws in dev via the
  runtime registry. A pure walk: no global state, no test-order coupling."
  [x]
  (cond
    (vector? x) (mapv strip-ce-property-props x)
    (map? x)
    (let [drop-set (get corpus-ce-property-attr-names (name (:tag x)))
          x        (if (and drop-set (:attrs x))
                     (let [attrs' (apply dissoc (:attrs x) drop-set)]
                       (if (seq attrs') (assoc x :attrs attrs') (dissoc x :attrs)))
                     x)]
      (if (:children x)
        (assoc x :children (mapv strip-ce-property-props (:children x)))
        x))
    :else x))

;; --- DEV-only host-root view-evidence annotation (rf2-hac8p) ----------------
;; The compiler stamps `data-rf2-source-coord` / `data-rf-view` on each view's
;; compiler-owned host root behind the DEBUG gate — goog.DEBUG on CLJS,
;; interop/debug-enabled? on the JVM. In THIS advanced bundle (goog.DEBUG=false)
;; the CLJS emitter DCEs the whole stamp, so the react side carries none. The
;; shared JVM truth, however, is computed in the compiler's OWN JVM where
;; interop/debug-enabled? is true (a compile-time regime, not a production SSR
;; build), so it carries the annotation. A real production SSR JVM
;; (`-Dre-frame.debug=false`) gates the SAME stamp OFF — so stripping the
;; DEV-only annotation from the JVM truth here recovers the true-production shape
;; and meets the advanced-CLJS side in one semantic space. This mirrors the CLJS
;; DCE exactly as `strip-ce-property-props` above mirrors the empty runtime
;; property registry. A pure walk: no global state, no test-order coupling.
(defn- strip-view-evidence
  [x]
  (cond
    (vector? x) (mapv strip-view-evidence x)
    (map? x)
    (let [x (if-let [a (:attrs x)]
              (let [a (dissoc a "data-rf2-source-coord" "data-rf-view")]
                (if (seq a) (assoc x :attrs a) (dissoc x :attrs)))
              x)]
      (if (:children x)
        (assoc x :children (mapv strip-view-evidence (:children x)))
        x))
    :else x))

;; ---------------------------------------------------------------------------
;; Tooth (i) — the build IS the elided / production regime
;; ---------------------------------------------------------------------------

(deftest debug-regime-is-off
  (testing "goog.DEBUG=false in-bundle — the corpus comparison below cannot
            silently run in the dev regime and pass vacuously"
    (is (false? interop/debug-enabled?)
        "interop/debug-enabled? must constant-fold to false in this advanced build")))

;; ---------------------------------------------------------------------------
;; Tooth (ii) — non-vacuity control: this bundle really is `:advanced` and
;; renaming unexterned reads (the binding_plan "advanced-build-really-renames"
;; pattern). Without it, a build that had stopped optimizing would let the
;; corpus comparison pass while proving nothing about the production emitter.
;; ---------------------------------------------------------------------------

;; `js-obj` sets properties by STRING (`goog.object.create`), so these keys are
;; dynamic — Closure can neither rename them nor read them as an object literal.
;; Whether the dotted read below finds its value therefore depends on ONE thing:
;; whether the READ was renamed.
(def ^:private probe (js-obj "corpusRenameProbe" "kept"))

;; The group local `node` is UNHINTED, so nothing externs `.corpusRenameProbe`
;; and `:advanced` renames the read away from the object's live string key.
(defview corpus-renaming-control-view
  [{:keys [probe/node]}]
  [:span {:data-role "corpus-renaming-control"} (str "[" (.-corpusRenameProbe node) "]")])

(deftest advanced-build-really-renames-unexterned-property-reads
  (testing "the probe's string key is intact (js-obj keys are unrenamable)"
    (is (= "kept" (unchecked-get probe "corpusRenameProbe"))))
  (testing "yet an UNHINTED dotted read of it renders empty — the read was renamed,
            so this bundle really is :advanced and the corpus proof below is real"
    (is (= "<span data-role=\"corpus-renaming-control\">[]</span>"
           (rds/renderToStaticMarkup
            (rt/jsx2 corpus-renaming-control-view (js-obj "probe/node" probe))))
        (str "rf-parity-corpus-advanced-renaming-live-v1: :advanced renamed the "
             "unexterned read, so advanced-CLJS == JVM-truth below is a genuine "
             "production proof, not a build that stopped optimizing"))))

;; ---------------------------------------------------------------------------
;; The proof — advanced-CLJS == JVM-truth per generated shape
;; ---------------------------------------------------------------------------

(deftest corpus-covers-every-case
  (is (= (count fx/cases) (count jvm-semantics)))
  (is (= (set (map :id fx/cases)) (set (keys jvm-semantics)))))

(deftest every-case-normalized-structural-equivalence
  (doseq [{:keys [id] :as c} fx/cases]
    (let [html   (render-case c)
          react  (strip-ce-property-props (ph/html->semantic html))
          jvm    (strip-view-evidence (ph/expand-trusted (get jvm-semantics id)))
          diff   (when (not= jvm react) (ph/first-divergence jvm react))]
      (is (= jvm react)
          (str "advanced-CLJS/JVM parity divergence in case " id
               "\n  first divergence: " (pr-str diff)
               "\n  react html: " html)))))

(deftest no-handler-attributes-ever
  ;; belt-and-braces over the parser's hard failure: the serialised markup of
  ;; handler-bearing cases carries no on* attribute names in production either
  (doseq [id [:handlers-on :handlers-off :spread-override :page]]
    (let [c    (some #(when (= id (:id %)) %) fx/cases)
          html (render-case c)]
      (is (nil? (re-find #"\son[A-Za-z-]+=" html))
          (str "handler attribute leaked into HTML for " id ": " html)))))
