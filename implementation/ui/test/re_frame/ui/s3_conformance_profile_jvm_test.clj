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
    3. the seven `re-frame.ui.react` interop-tier WRAPPERS (six host-hook
       fail-loud stubs + the def-level `lazy` macro).

  For each entry it names the direct-call/kind contract the CODE must honour on
  the JVM and the gate/proof home that proves the entry's real runtime
  behaviour (runtime behaviour itself is proven by those gates — browser / JVM /
  elision suites — not re-asserted here).

  The catalogue is EXACT, not a superset: exact-set guards assert that the
  public vars of `re-frame.ui` and `re-frame.ui.react` are EXACTLY the
  catalogued surface (S3 entries + explicitly-listed non-S3 publics), so ANY
  addition / removal / rename of a public var fails the test until the catalogue
  is updated in lockstep. The human profile
  (spec/conformance/S3-view-conformance-profile.md) is validated ROW BY ROW —
  each frozen verb's §1 table row must name its direct-call error AND its gate,
  and the §6 gate roster must name exactly the certified S3 arms — rather than by
  whole-document token presence, so moving a name into unrelated prose or
  swapping a gate is caught."
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
                :contract     "ordinary compiled defview; real <a href>; plain left-click dispatches :source :router; native/modifier clicks defer to the browser"}})

;; ---------------------------------------------------------------------------
;; (3) The frozen `re-frame.ui.react` INTEROP TIER — seven wrappers (Spec 004
;; §The React interop tier). NOT a second state/reactivity model. Six are HOST
;; HOOKS: `(defn …)` stubs the compiler recognises by resolved head and lowers,
;; and which fail loud on a direct call. `lazy` is a DEF-LEVEL macro. Proof
;; homes: JVM structural subset + compile-time position law + HMR-signature ->
;; re-frame.ui.react-interop-jvm-test; real React client behaviour ->
;; re-frame.ui.react-interop-dom-cljs-test.
;; ---------------------------------------------------------------------------
(def frozen-react-wrappers
  {'use-ref           {:kind :hook :error :rf.error/ui-tree-malformed}
   'use-effect        {:kind :hook :error :rf.error/ui-tree-malformed}
   'use-layout-effect {:kind :hook :error :rf.error/ui-tree-malformed}
   'use-effect-event  {:kind :hook :error :rf.error/ui-tree-malformed}
   'use-context       {:kind :hook :error :rf.error/ui-tree-malformed}
   'use-id            {:kind :hook :error :rf.error/ui-tree-malformed}
   'lazy              {:kind :macro}})

;; Public vars of `re-frame.ui` that are NOT part of the S3 surface — the boot
;; adapter + the S1/S1b/S1c/S2 forms, catalogued in the Spec 004 family. Listed
;; explicitly so the facade exact-set guard forces every NEW public var to be
;; classified (S3 verb, S3 view, or here) before the surface can silently drift.
(def non-s3-facade-publics
  '#{adapter defview custom-element mount create-root render! hydrate-root
     unmount! frame-root frame-provider sub lease frame raw html raw-fn spread})

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
      use-ref           (react/use-ref)
      use-effect        (react/use-effect nil)
      use-layout-effect (react/use-layout-effect nil)
      use-effect-event  (react/use-effect-event nil)
      use-context       (react/use-context nil)
      use-id            (react/use-id))
    ::no-throw
    (catch clojure.lang.ExceptionInfo e (:rf.error/id (ex-data e)))))

(deftest react-wrappers-resolve-with-frozen-kinds
  (testing "the seven interop wrappers resolve; the six hooks fail loud, lazy is a macro"
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

(defn- repo-root []
  (loop [d (io/file (System/getProperty "user.dir"))]
    (cond
      (nil? d) nil
      (.exists (io/file d "spec" "conformance")) d
      :else (recur (.getParentFile d)))))

(defn- profile-doc []
  (io/file (repo-root) "spec" "conformance" "S3-view-conformance-profile.md"))

(defn- table-row-for
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

(defn- roster-section
  "The §6 gate-roster region of `text` (from the `## 6.` header to `## 7.`), so a
  gate is read from a real roster row, not from incidental prose elsewhere."
  [text]
  (let [start (str/index-of text "## 6.")
        end   (str/index-of text "## 7.")]
    (when (and start end) (subs text start end))))

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
