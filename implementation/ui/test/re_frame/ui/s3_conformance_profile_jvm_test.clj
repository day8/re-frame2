(ns re-frame.ui.s3-conformance-profile-jvm-test
  "The S3 view-substrate CONFORMANCE PROFILE, executable (07 §5; EP-0035;
  rf2-vxgfnd.95.10).

  This is the single authoritative, EXACT catalogue of the FROZEN S3 surface.
  It pins THREE runtime homes precisely:

    1. the ten compiler-owned VERBS of the `re-frame.ui` facade — each a
       `(defn …)` stub that exists for symbol resolution only and FAILS LOUD
       when called outside compiler lowering;
    2. `ui/route-link` — the ONE framework-authored S3 VIEW (an ordinary
       compiled `defview`, NOT a fail-loud stub and NOT a compiler intrinsic);
    3. the six `re-frame.ui.react` interop-tier WRAPPERS (five host-hook
       fail-loud stubs + the def-level `lazy` macro). The everyday DOM-node ref
       is NOT here — it is the substrate `ui/ref` (a re-frame.ui public,
       promoted out of the interop tier, rf2-u53yy.9).

  For each entry it names the direct-call/kind contract the CODE must honour on
  the JVM and the gate/proof home that proves the entry's real runtime
  behaviour (runtime behaviour itself is proven by those gates — browser / JVM /
  elision suites — not re-asserted here).

  The catalogue is EXACT, not a superset: exact-set guards assert that the
  public vars of `re-frame.ui` and `re-frame.ui.react` are EXACTLY the
  catalogued surface (S3 entries + explicitly-listed non-S3 publics), so ANY
  addition / removal / rename of a public var fails the test until the catalogue
  is updated in lockstep. The human profile
  (spec/conformance/S3-view-conformance-profile.md) is validated ROW BY ROW for
  ALL THREE rosters — each frozen verb's §1.1 row must name its direct-call error
  AND its gate; the §1.2 `route-link` row its view-id, absent-artefact error,
  contract fragments AND both proof homes; each §1.3 wrapper row its host
  primitive, kind, direct-call behaviour AND contract fragments (with §1.3 naming
  the shared JVM/CLJS proof homes); and the §6 gate roster exactly the certified
  S3 arms — rather than by whole-document token presence, so deleting a row,
  moving a name into unrelated prose, or swapping a contract, proof home or gate
  between rows is caught."
  (:require [clojure.java.io :as io]
            [clojure.set :as set]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [re-frame.ui :as ui]
            [re-frame.ui.react :as react]))

;; ---------------------------------------------------------------------------
;; (1) The ten compiler-owned S3 VERBS of the re-frame.ui facade. verb-symbol ->
;; the direct-call error id, the gate that proves its runtime behaviour, and the
;; one-line contract. Each facade entry is a fail-loud `(defn …)` stub (NO
;; `:rf.ui/view` metadata — that is what distinguishes a verb from route-link).
;; This map is the source of truth the profile document's §1 table restates.
;; ---------------------------------------------------------------------------
(def frozen-s3-verbs
  {'local          {:error :rf.error/ui-tree-malformed :gate "G-15"
                    :contract "[value set! update!] three-tuple; update! composes over the latest host state"}
   'effect         {:error :rf.error/ui-tree-malformed :gate "G-6"
                    :contract "value-deps / :connect host effect; cleanup honoured; JVM records capability only"}
   'dispatch-fn    {:error :rf.error/ui-tree-malformed :gate "G-8"
                    :contract "stable committed-frame dispatcher; fails loud after disconnect"}
   'event          {:error :rf.error/ui-tree-malformed :gate "G-8"
                    :contract "committed handler; a vector result rides the sync door at a controlled site; nil = no dispatch"}
   'handler        {:error :rf.error/ui-tree-malformed :gate "G-8"
                    :contract "imperative committed callback; per-site stable; result ignored"}
   'render-fn      {:error :rf.error/ui-tree-malformed :gate "G-16"
                    :contract "pure compiled render-slot callback for an internal library seam"}
   'slot           {:error :rf.error/ui-tree-malformed :gate "G-16"
                    :contract "compiler-owned ui/render-fn invocation; nil renders nothing"}
   'spread-safe    {:error :rf.error/ui-spread-outside-template :gate "G-17"
                    :contract "literal safe-spread policy; owned-key deny in every build; retains the sync door"}
   'error-boundary {:error :rf.error/ui-tree-malformed :gate "G-6"
                    :contract "explicit error component; :on-error dispatches after commit; :reset-key retries"}
   'client-only    {:error :rf.error/ui-tree-malformed :gate "G-7"
                    :contract "browser-only subtree with a mandatory capability-free fallback"}})

;; ---------------------------------------------------------------------------
;; (2) route-link — the ONE framework-authored S3 VIEW. An ORDINARY compiled
;; `defview` over the routing-owned late-bound link seam (`:routing/link-model` /
;; `:routing/activate-link!`), NOT a compiler intrinsic and NOT a fail-loud stub:
;; its var carries `:rf.ui/view` metadata + a registered view-id, and rendering
;; it without the routing artefact fails loud with :rf.error/routing-artefact-
;; missing. The click/href law + conformance matrix live in Spec 012 (routing
;; owns the law); the JVM/SSR shell + client render homes are named here.
;; ---------------------------------------------------------------------------
(def frozen-s3-view
  {'route-link {:view-id      :re-frame.ui/route-link
                :absent-error :rf.error/routing-artefact-missing
                :jvm-proof    "re-frame.ui.route-link-jvm-test"
                :cljs-proof   "re-frame.ui.route-link-dom-cljs-test"
                :contract     "ordinary compiled defview; real <a href>; plain left-click dispatches :source :router; native/modifier clicks defer to the browser"
                ;; The small EXACT fragments the §1.2 row must carry, so swapping
                ;; the contract out of the row (or into another row) is red.
                :row-fragments ["ordinary compiled `defview`"
                                "not a fail-loud stub"
                                "real `<a href>`"
                                "`:source :router`"
                                "native/modifier clicks defer to the browser"]}})

;; ---------------------------------------------------------------------------
;; (3) The frozen `re-frame.ui.react` INTEROP TIER — six wrappers (Spec 004
;; §The React interop tier). NOT a second state/reactivity model. Five are HOST
;; HOOKS: `(defn …)` stubs the compiler recognises by resolved head and lowers,
;; and which fail loud on a direct call. `lazy` is a DEF-LEVEL macro. The
;; everyday DOM-node ref left this tier for the substrate `ui/ref` (rf2-u53yy.9),
;; catalogued as a re-frame.ui public in `non-s3-facade-publics`. Proof homes:
;; JVM structural subset + compile-time position law + HMR-signature ->
;; re-frame.ui.react-interop-jvm-test; real React client behaviour ->
;; re-frame.ui.react-interop-dom-cljs-test.
;; ---------------------------------------------------------------------------
;; `:host` is the wrapped React primitive the row must name; `:row-fragments`
;; are the small EXACT contract fragments the §1.3 row must carry (so swapping
;; two wrappers' contracts, or hollowing one out, is red). All six share the
;; two proof homes, but each entry names its own so dropping or swapping a proof
;; association is red per wrapper rather than tier-wide.
(def frozen-react-wrappers
  {'use-effect        {:kind :hook :error :rf.error/ui-tree-malformed :host "useEffect"
                       :jvm-proof "re-frame.ui.react-interop-jvm-test"
                       :cljs-proof "re-frame.ui.react-interop-dom-cljs-test"
                       :row-fragments ["passive after-paint effect"
                                       "setup returns a cleanup"
                                       "`[]` = connect-only"]}
   'use-layout-effect {:kind :hook :error :rf.error/ui-tree-malformed :host "useLayoutEffect"
                       :jvm-proof "re-frame.ui.react-interop-jvm-test"
                       :cljs-proof "re-frame.ui.react-interop-dom-cljs-test"
                       :row-fragments ["after DOM mutation"
                                       "the measure-before-paint door"]}
   ;; React 19.2 `useEffectEvent` — latest committed values, UNSTABLE identity
   ;; (a fresh fn each render), no deps array, never in a deps vector, never
   ;; called during render. Shipped native with no shim (hooks.cljc), proven in
   ;; re-frame.ui.react-interop-dom-cljs-test.
   'use-effect-event  {:kind :hook :error :rf.error/ui-tree-malformed :host "useEffectEvent"
                       :jvm-proof "re-frame.ui.react-interop-jvm-test"
                       :cljs-proof "re-frame.ui.react-interop-dom-cljs-test"
                       :row-fragments ["latest render's committed values"
                                       "identity is **not** stable"
                                       "a fresh fn each render"
                                       "takes no deps"
                                       "must never appear in a deps vector nor be called during render"]}
   'use-context       {:kind :hook :error :rf.error/ui-tree-malformed :host "useContext"
                       :jvm-proof "re-frame.ui.react-interop-jvm-test"
                       :cljs-proof "re-frame.ui.react-interop-dom-cljs-test"
                       :row-fragments ["**foreign** Context object"
                                       "never React context"]}
   'use-id            {:kind :hook :error :rf.error/ui-tree-malformed :host "useId"
                       :jvm-proof "re-frame.ui.react-interop-jvm-test"
                       :cljs-proof "re-frame.ui.react-interop-dom-cljs-test"
                       :row-fragments ["host-generated tree-positional id token"
                                       "SSR determinism rides the root contract"]}
   'lazy              {:kind :macro :host "React.lazy"
                       :jvm-proof "re-frame.ui.react-interop-jvm-test"
                       :cljs-proof "re-frame.ui.react-interop-dom-cljs-test"
                       :row-fragments ["code-splitting over a foreign `load-thunk`"
                                       "not a loading-orchestration surface"]}})

;; Public vars of `re-frame.ui` that are NOT part of the S3 surface — the boot
;; adapter + the S1/S1b/S1c/S2 forms, catalogued in the Spec 004 family. Listed
;; explicitly so the facade exact-set guard forces every NEW public var to be
;; classified (S3 verb, S3 view, or here) before the surface can silently drift.
(def non-s3-facade-publics
  '#{adapter defview custom-element mount create-root render! hydrate-root
     unmount! frame-root frame-provider sub frame raw html raw-fn spread
     ;; ui/ref (rf2-u53yy.9): the substrate-native DOM-node host hook, promoted
     ;; out of the re-frame.ui.react interop tier (which lost its ref hook). A
     ;; fail-loud `(defn …)` stub like `local`, lowered by the same finite-site
     ;; machinery; its runtime behaviour rides the react-interop JVM/DOM suites
     ;; (not an S3 verb-arm gate), so it is catalogued here rather than in
     ;; frozen-s3-verbs.
     ref
     ;; S4 presence (rf2-uckeg): the enter/exit retention template form + its
     ;; single phase read — not part of the S3 surface
     presence presence-phase
     ;; S5 render-static (rf2-oo5lb): the pure :server static-HTML render macro —
     ;; not part of the S3 surface (the S4 guard partitions it into its S5 bucket)
     render-static
     ;; S6 ->react (rf2-u53yy.2): the OUTWARD interop bridge — export a compiled
     ;; view as a foreign React component. Not part of the S3 surface; CLJS
     ;; delegates to re-frame.ui.runtime/->react-component, a JVM call is a host-op
     ->react})

;; The S3-specific gate arms this profile certifies (07 §5 / EP-0034 §4): every
;; verb gate PLUS the two non-verb S3 arms — G-11 (HMR shell + view-evidence /
;; manifest production absence) and G-18 (library-façade isolation). The §6
;; roster must name exactly this set.
(def s3-arm-gates
  #{"G-6" "G-7" "G-8" "G-11" "G-15" "G-16" "G-17" "G-18"})

;; ===========================================================================
;; Executable surface pins — the shipped code honours each catalogued contract.
;; ===========================================================================

(defn- direct-call!
  "Invoke the facade stub for `verb` directly (outside compiler lowering) so it
  fails loud, and return the thrown `:rf.error/id`, or ::no-throw."
  [verb]
  (try
    (case verb
      local          (ui/local 0)
      effect         (ui/effect)
      dispatch-fn    (ui/dispatch-fn)
      event          (ui/event)
      handler        (ui/handler)
      render-fn      (ui/render-fn)
      slot           (ui/slot)
      spread-safe    (ui/spread-safe nil nil)
      error-boundary (ui/error-boundary)
      client-only    (ui/client-only))
    ::no-throw
    (catch clojure.lang.ExceptionInfo e (:rf.error/id (ex-data e)))))

(deftest s3-verbs-resolve-as-fail-loud-facade-stubs
  (testing "each frozen S3 verb is a public fail-loud facade stub (not a view)"
    (doseq [[verb {:keys [error]}] frozen-s3-verbs]
      (let [v (ns-resolve 're-frame.ui verb)]
        (is (some? v) (str "ui/" verb " must resolve in the re-frame.ui facade"))
        (is (not (:rf.ui/view (meta v)))
            (str "ui/" verb " is a compiler-verb stub, not a compiled view"))
        (is (= error (direct-call! verb))
            (str "(" verb " …) called directly must fail loud with " error))))))

(deftest route-link-is-a-compiled-view-not-a-stub
  (testing "route-link is an ordinary compiled defview, not a fail-loud stub"
    (doseq [[view {:keys [view-id]}] frozen-s3-view]
      (let [v (ns-resolve 're-frame.ui view)]
        (is (some? v) (str "ui/" view " must resolve in the re-frame.ui facade"))
        (is (true? (:rf.ui/view (meta v)))
            (str "ui/" view " must be a compiled view (carry :rf.ui/view)"))
        (is (= view-id (:rf.ui/view-id (meta v)))
            (str "ui/" view " must register view-id " view-id))
        (is (not (contains? frozen-s3-verbs view))
            (str "ui/" view " must not be double-classified as a fail-loud verb"))))))

(defn- react-direct-call!
  "Invoke a `re-frame.ui.react` HOOK wrapper directly (at a supported arity) so
  it fails loud, and return the thrown `:rf.error/id`, or ::no-throw."
  [wrapper]
  (try
    (case wrapper
      use-effect        (react/use-effect nil)
      use-layout-effect (react/use-layout-effect nil)
      use-effect-event  (react/use-effect-event nil)
      use-context       (react/use-context nil)
      use-id            (react/use-id))
    ::no-throw
    (catch clojure.lang.ExceptionInfo e (:rf.error/id (ex-data e)))))

(deftest react-wrappers-resolve-with-frozen-kinds
  (testing "the six interop wrappers resolve; the five hooks fail loud, lazy is a macro"
    (doseq [[wrapper {:keys [kind error]}] frozen-react-wrappers]
      (let [v (ns-resolve 're-frame.ui.react wrapper)]
        (is (some? v) (str "react/" wrapper " must resolve in re-frame.ui.react"))
        (case kind
          :hook  (do (is (not (:macro (meta v)))
                         (str "react/" wrapper " is a host-hook fn, not a macro"))
                     (is (= error (react-direct-call! wrapper))
                         (str "(react/" wrapper " …) called directly must fail loud with " error)))
          :macro (is (true? (:macro (meta v)))
                     (str "react/" wrapper " must be a def-level macro")))))))

;; ===========================================================================
;; Exact-set guards — the add / remove / rename teeth on the runtime surface.
;; ===========================================================================

(deftest facade-surface-is-exactly-the-catalogue
  (testing "re-frame.ui publics are EXACTLY the catalogued surface — a new / removed / renamed public var fails until the catalogue is updated in lockstep"
    (let [publics    (set (keys (ns-publics 're-frame.ui)))
          catalogued (set/union (set (keys frozen-s3-verbs))
                                (set (keys frozen-s3-view))
                                non-s3-facade-publics)]
      (is (= catalogued publics)
          (str "re-frame.ui surface drift — "
               "uncatalogued new publics: " (sort (set/difference publics catalogued))
               "; catalogued-but-absent: " (sort (set/difference catalogued publics)))))))

(deftest react-tier-is-exactly-the-catalogue
  (testing "re-frame.ui.react publics are EXACTLY the seven frozen wrappers"
    (let [publics    (set (keys (ns-publics 're-frame.ui.react)))
          catalogued (set (keys frozen-react-wrappers))]
      (is (= catalogued publics)
          (str "re-frame.ui.react surface drift — "
               "uncatalogued new publics: " (sort (set/difference publics catalogued))
               "; catalogued-but-absent: " (sort (set/difference catalogued publics)))))))

(deftest verb-gates-are-certified-s3-arms
  (testing "every S3 verb names a gate in the certified S3-arm roster (no phantom verb gate)"
    (doseq [[verb {:keys [gate]}] frozen-s3-verbs]
      (is (contains? s3-arm-gates gate)
          (str "ui/" verb " names gate " gate " which is not a certified S3 arm")))))

;; ===========================================================================
;; Document drift guard — the human profile stays EXACT against this surface,
;; validated row by row rather than by whole-document token presence.
;; ===========================================================================

;; `repo-root`, `table-row-for` and `section-between` are PUBLIC so the sibling
;; stage profiles compose with this guard instead of duplicating its row-binding
;; machinery (rf2-yho9j: the S4 guard reuses all three). Behaviour unchanged.
(defn repo-root []
  (loop [d (io/file (System/getProperty "user.dir"))]
    (cond
      (nil? d) nil
      (.exists (io/file d "spec" "conformance")) d
      :else (recur (.getParentFile d)))))

(defn- profile-doc []
  (io/file (repo-root) "spec" "conformance" "S3-view-conformance-profile.md"))

(defn table-row-for
  "The single Markdown table row whose FIRST cell is exactly `token`, or nil when
  there is not exactly one. Keying on the first cell (not mere occurrence) binds
  a verb to its OWN §1 catalogue row: prose mentions, roster rows that name a
  bare token without the `ui/` qualifier, and rows that merely reference the verb
  mid-contract (e.g. the `ui/slot` row naming `ui/render-fn`) do not match."
  [lines token]
  (let [first-cell (fn [line]
                     (let [t (str/triml line)]
                       (when (str/starts-with? t "|")
                         (-> t (subs 1) (str/split #"\|") first str/trim))))
        rows       (filter #(= token (first-cell %)) lines)]
    (when (= 1 (count rows)) (first rows))))

(defn section-between
  "The region of `text` from header `from` up to header `to`, so a claim is read
  from the section that owns it rather than from incidental prose elsewhere."
  [text from to]
  (let [start (str/index-of text from)
        end   (str/index-of text to)]
    (when (and start end (< start end)) (subs text start end))))

(defn- roster-section
  "The §6 gate-roster region of `text` (from the `## 6.` header to `## 7.`), so a
  gate is read from a real roster row, not from incidental prose elsewhere."
  [text]
  (section-between text "## 6." "## 7."))

(defn- interop-section
  "The §1.3 React-interop-tier region — the section that owns the shared wrapper
  proof homes (they are stated once for the tier, not per row)."
  [text]
  (section-between text "### 1.3" "## 2."))

(deftest profile-catalogues-every-verb-row-exactly
  (testing "each frozen S3 verb has one §1 table row naming its direct-call error AND its gate"
    (let [doc (profile-doc)]
      (is (some? (repo-root)) "must locate the repo root (spec/conformance)")
      (is (.exists doc) (str "the profile document must exist at " doc))
      (let [lines (str/split-lines (slurp doc))]
        (doseq [[verb {:keys [error gate]}] frozen-s3-verbs]
          (let [token (str "`ui/" verb "`")
                row   (table-row-for lines token)]
            (is (some? row)
                (str "the profile must catalogue " token " in exactly one table row"))
            (when row
              (is (str/includes? row (str error))
                  (str token "'s row must name its direct-call error " error))
              (is (str/includes? row (str "**" gate "**"))
                  (str token "'s row must name its proving gate " gate)))))))))

(deftest profile-catalogues-the-framework-view-row-exactly
  (testing "the §1.2 route-link row names its view-id, absent-artefact error, contract, and BOTH proof homes"
    (let [lines (str/split-lines (slurp (profile-doc)))]
      (doseq [[view {:keys [view-id absent-error jvm-proof cljs-proof row-fragments]}] frozen-s3-view]
        (let [token (str "`ui/" view "`")
              row   (table-row-for lines token)]
          (is (some? row)
              (str "the profile must catalogue " token " in exactly one table row"))
          (when row
            (is (str/includes? row (str view-id))
                (str token "'s row must name its registered view-id " view-id))
            (is (str/includes? row (str absent-error))
                (str token "'s row must name its absent-artefact error " absent-error))
            (is (str/includes? row jvm-proof)
                (str token "'s row must name its JVM proof home " jvm-proof))
            (is (str/includes? row cljs-proof)
                (str token "'s row must name its CLJS proof home " cljs-proof))
            (doseq [fragment row-fragments]
              (is (str/includes? row fragment)
                  (str token "'s row must carry its contract fragment: " fragment)))))))))

(deftest profile-catalogues-every-react-wrapper-row-exactly
  (testing "each frozen wrapper has one §1.3 row naming its host primitive, kind, direct-call behaviour, and contract"
    (let [lines (str/split-lines (slurp (profile-doc)))]
      (doseq [[wrapper {:keys [kind error host row-fragments]}] frozen-react-wrappers]
        (let [token (str "`react/" wrapper "`")
              row   (table-row-for lines token)]
          (is (some? row)
              (str "the profile must catalogue " token " in exactly one table row"))
          (when row
            (is (str/includes? row (str "`" host "`"))
                (str token "'s row must name the host primitive it wraps: " host))
            (case kind
              :hook  (do (is (str/includes? row "host hook")
                             (str token "'s row must classify it as a host hook"))
                         (is (str/includes? row (str error))
                             (str token "'s row must name its direct-call error " error)))
              :macro (do (is (str/includes? row "def-level macro")
                             (str token "'s row must classify it as a def-level macro"))
                         (is (str/includes? row "compile error")
                             (str token "'s row must state its compile-error direct-call behaviour"))))
            (doseq [fragment row-fragments]
              (is (str/includes? row fragment)
                  (str token "'s row must carry its contract fragment: " fragment)))))))))

(deftest profile-names-the-react-wrapper-proof-homes
  (testing "§1.3 names the JVM + CLJS proof homes every wrapper rides — dropping or swapping one is red"
    (let [section (interop-section (slurp (profile-doc)))]
      (is (some? section) "the profile must have a §1.3 interop-tier section and a §2 header")
      (when section
        (doseq [[wrapper {:keys [jvm-proof cljs-proof]}] frozen-react-wrappers]
          (is (str/includes? section jvm-proof)
              (str "§1.3 must name " wrapper "'s JVM proof home " jvm-proof))
          (is (str/includes? section cljs-proof)
              (str "§1.3 must name " wrapper "'s CLJS proof home " cljs-proof)))
        ;; the JVM home proves three distinct things; naming it is not enough
        (doseq [aspect ["structural subset" "position law" "HMR signature"]]
          (is (str/includes? section aspect)
              (str "§1.3 must state the JVM proof home covers the " aspect)))))))

(deftest profile-gate-roster-is-exactly-the-arm-set
  (testing "the §6 gate roster names EXACTLY the certified S3 arms — dropping / adding / renaming a roster gate is red"
    (let [text   (slurp (profile-doc))
          roster (roster-section text)]
      (is (some? roster) "the profile must have a §6 gate roster and a §7 header")
      (when roster
        (let [named (set (map second (re-seq #"\*\*(G-\d+)\*\*" roster)))]
          (is (= s3-arm-gates named)
              (str "§6 gate-roster drift — "
                   "extra: " (sort (set/difference named s3-arm-gates))
                   "; missing: " (sort (set/difference s3-arm-gates named)))))))))

(deftest profile-documents-the-non-surface-wall
  (testing "the profile enumerates the explicit S3 non-surfaces (the wall)"
    (is (str/includes? (slurp (profile-doc)) "Non-surfaces")
        "the profile must enumerate explicit S3 non-surfaces")))
