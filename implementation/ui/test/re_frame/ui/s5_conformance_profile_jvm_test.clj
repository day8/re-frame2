(ns re-frame.ui.s5-conformance-profile-jvm-test
  "The S5 view-substrate CONFORMANCE PROFILE, executable (07 §5; EP-0034;
  rf2-vxgfnd.97.3 — the S5 end-of-stage gate of epic rf2-vxgfnd.97). THE MERGE
  OF THIS LEAF IS THE RULED S6 ENTRY TOKEN (rf2-vxgfnd.98).

  This is the single authoritative, EXACT catalogue of the FROZEN S5 surface —
  the compiled-view substrate's server-rendered-roots stage. S5 completes the
  server/hydration BEHAVIOUR over an already-blessed mount surface and adds one
  new facade name (`render-static`). It fixes FIVE shipped rows:

    1. the ROOT MANIFEST v1 (the additive per-root wire — a strict superset of
       the Root Descriptor) and its POSITIONAL discovery;
    2. HYDRATION — preflight + idempotent payload ADOPTION (byte-identical
       fallback trees adopt with no repaint; a divergence is
       `:rf.ssr/hydration-mismatch`);
    3. FAILED-ROOT ISOLATION — one root's whole boot is the isolation unit;
       siblings still hydrate and stay interactive;
    4. `re-frame.ssr/emit-ui-tree` — the pure tree -> HTML serialiser (validates
       `:rf.ui/tree-version` FIRST; calls no view);
    5. the root-scoped `ui/client-only` PHASE FLIP (boot `:server`, one
       root-scoped write to `:client`, one update; failed roots never flip).

  Plus one OUTSTANDING blessed name (§3): `render-static` is a blessed v1/S5
  public of `re-frame.ui` with NO implementation site anywhere under
  implementation/ — the names-without-implementations failure mode the S5
  audit caught. The MERGE PRECONDITION is fail-closed: every blessed S5 facade
  name must RESOLVE. `render-static` does not, so
  `merge-precondition-blessed-s5-facade-names-resolve` is RED BY DESIGN and the
  gate cannot pass while the name is a phantom. The red clears when it ships.

  Runtime behaviour itself is proven by the named proof homes (JVM / node /
  browser suites under implementation/ssr/test), not re-asserted here. What this
  guard adds is BINDING: every human row in
  spec/conformance/S5-view-conformance-profile.md is validated ROW BY ROW
  against `frozen-s5-surface` — keyed on the row's FIRST table cell, so prose
  mentions elsewhere do not satisfy a row — and every named proof home must be a
  real test namespace on disk. Deleting a row, hollowing one out, or swapping a
  proof home between rows is red.

  COMPOSITION, not duplication: like the S4 guard reuses the S3 guard's
  row-binding machinery, this reuses BOTH (repo-root / table-row-for /
  section-between) and their published frozen rosters, so it can assert the S1-S4
  rows are NOT restated here (duplication IS drift). It does not restate — nor
  edit — the S3/S4 surfaces."
  (:require [clojure.java.io :as io]
            [clojure.set :as set]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [re-frame.ui :as ui]
            ;; COMPOSITION: the S3 guard owns the row-binding machinery
            ;; (repo-root / table-row-for / section-between) AND the frozen
            ;; S1-S3 rosters; the S4 guard owns the S4 delta. This profile
            ;; reuses both to bind its rows and to prove it restates neither.
            [re-frame.ui.s3-conformance-profile-jvm-test :as s3]
            [re-frame.ui.s4-conformance-profile-jvm-test :as s4]))

;; ---------------------------------------------------------------------------
;; (1) The frozen S5 SURFACE — the five shipped rows. Each entry names the
;; profile row's FIRST CELL (`:token`; rows are keyed on it), the section it
;; lives in, the small EXACT contract fragments its human row must carry (enough
;; that swapping two rows' contracts, or hollowing one out, is red; not so many
;; that ordinary rewording is), the loud-failure ids the row must name, and the
;; REAL proof-home test namespaces (under implementation/ssr/test) that prove the
;; row's runtime behaviour. This map is the source of truth §1 restates.
;; ---------------------------------------------------------------------------

(def frozen-s5-surface
  {:root-manifest
   {:token         "`Root Manifest v1`"
    :errors        [:rf.error/root-manifest-invalid :rf.error/frame-payload-conflict]
    :proofs        ["re-frame.ssr.root-manifest-cljs-test"]
    :row-fragments ["every extension key is OPTIONAL"
                    "an unmodified S1 descriptor is already a valid manifest"
                    "strict superset"
                    "round-trips exactly"]}

   :manifest-discovery
   {:token         "`manifest discovery`"
    :errors        [:rf.error/root-manifest-invalid]
    :proofs        ["re-frame.ssr.hydrate-root-seam-dom-cljs-test"]
    :row-fragments ["positionally"
                    ":ssr/discover-root-manifest"
                    "{:missing :manifest}"]}

   :hydration-adoption
   {:token         "`hydration preflight + adoption`"
    :errors        [:rf.ssr/hydration-mismatch]
    :proofs        ["re-frame.ssr.client-only-adoption-verification-dom-cljs-test"
                    "re-frame.ssr.multi-root-hydration-dom-cljs-test"]
    :row-fragments ["installs the payload/state"
                    "adopted cleanly with no re-render"
                    "never a silent repaint"]}

   :failed-root-isolation
   {:token         "`failed-root isolation`"
    :errors        []
    :proofs        ["re-frame.ssr.failed-root-isolation-cljs-test"
                    "re-frame.ssr.failed-root-isolation-dom-cljs-test"]
    :row-fragments ["one root's whole boot is the isolation unit"
                    "siblings still hydrate"
                    "no page-wide barrier"]}

   :emit-ui-tree
   {:token         "`re-frame.ssr/emit-ui-tree`"
    :errors        [:rf.error/ssr-ui-tree-version-unsupported :rf.error/ui-tree-malformed]
    :proofs        ["re-frame.ssr.emit-ui-tree-cljs-test"]
    :row-fragments ["already-rendered"
                    "it calls nothing"
                    "before any emission"]}

   :phase-flip
   {:token         "`client-only phase flip`"
    :errors        []
    :proofs        ["re-frame.ssr.phase-flip-hydration-dom-cljs-test"]
    :row-fragments ["boots in `:server` phase"
                    "single root-scoped write"
                    "non-conforming"
                    "failed root never flips"
                    "onRecoverableError"]}})

;; ---------------------------------------------------------------------------
;; (2) The BLESSED S5 facade names — the merge-precondition roster. S5 adds one
;; new public name to `re-frame.ui`; the fail-closed rule (rf2-vxgfnd.97.3) is
;; that every S5 public name the spec/API.md blessed-surface freeze claims must
;; have a real implementation site. This guard enforces the RESOLUTION half:
;; each must resolve as a var. `render-static` is blessed (API.md §2 row +
;; §2b Stage=S5) but UNIMPLEMENTED, so the check below is RED BY DESIGN until it
;; ships — the gate cannot pass on a phantom surface.
;; ---------------------------------------------------------------------------

(def blessed-s5-facade-names
  "The `re-frame.ui` public names blessed for the S5 stage that this gate holds
  fail-closed. `render-static` is the one name NEW at S5; the other mount-surface
  verbs (`hydrate-root` / `create-root` / `render!` / `mount` / `unmount!`) and
  `client-only` shipped their signatures earlier and are inherited by reference,
  so they are not re-listed — the S3/S4 facade exact-set guards already pin them."
  '#{render-static})

;; The frozen S4 profile this one inherits by exact reference. A broken
;; inheritance link — the file gone, or the reference dropped from §0 — is red.
(def s5-inherits-profile "S4-view-conformance-profile.md")

;; ===========================================================================
;; Shared with the S3/S4 guards — see the :require note above.
;; ===========================================================================

(def ^:private repo-root s3/repo-root)
(def ^:private table-row-for s3/table-row-for)
(def ^:private section-between s3/section-between)

(defn- profile-doc []
  (io/file (repo-root) "spec" "conformance" "S5-view-conformance-profile.md"))

(defn- s4-profile-doc []
  (io/file (repo-root) "spec" "conformance" "S4-view-conformance-profile.md"))

(defn- doc-text [] (slurp (profile-doc)))
(defn- doc-lines [] (str/split-lines (doc-text)))

;; ===========================================================================
;; Inheritance — composes with the S3/S4 guards: a broken inheritance link, a
;; restated S1-S4 row, or a moved/deleted proof home must fail loudly.
;; ===========================================================================

(deftest inheritance-link-is-intact
  (testing "the frozen S4 profile this one inherits still exists"
    (is (.exists (s4-profile-doc))
        (str "broken inheritance link — " s5-inherits-profile " is missing")))
  (testing "§0 inherits S1-S4 by EXACT reference to the frozen S4 profile"
    (let [section (section-between (doc-text) "## 0." "## 1.")]
      (is (some? section) "the profile must have a §0 inheritance section and a §1 header")
      (when section
        (doseq [claim [s5-inherits-profile
                       "cumulative"
                       "by exact reference"
                       "does not restate"]]
          (is (str/includes? section claim)
              (str "§0 must state: " claim)))))))

(deftest profile-does-not-restate-the-s1-s4-rows
  (testing "the S5 profile must not restate any frozen S1-S4 row — duplication IS drift"
    (let [lines (doc-lines)]
      ;; S3 verbs + the framework view
      (doseq [sym (concat (keys s3/frozen-s3-verbs) (keys s3/frozen-s3-view))]
        (is (nil? (table-row-for lines (str "`ui/" sym "`")))
            (str "the S5 profile must not restate the S3 row for ui/" sym
                 " — S1-S4 are inherited by reference (§0)")))
      ;; the React interop tier
      (doseq [sym (keys s3/frozen-react-wrappers)]
        (is (nil? (table-row-for lines (str "`react/" sym "`")))
            (str "the S5 profile must not restate the S3 row for react/" sym)))
      ;; the S4 delta forms (presence / custom-element / raw / html / …)
      (doseq [{:keys [token]} (vals s4/frozen-s4-forms)]
        (is (nil? (table-row-for lines token))
            (str "the S5 profile must not restate the S4 row for " token))))))

;; ===========================================================================
;; Row-by-row document drift guard — the human profile stays EXACT against the
;; frozen surface, validated per row rather than by whole-document token presence.
;; ===========================================================================

(deftest profile-document-exists
  (is (some? (repo-root)) "must locate the repo root (spec/conformance)")
  (is (.exists (profile-doc))
      (str "the S5 profile document must exist at " (profile-doc))))

(deftest profile-catalogues-every-s5-row-exactly
  (testing "each frozen S5 surface has one §1 row naming its errors, proof homes and contract"
    (let [lines (doc-lines)]
      (doseq [[k {:keys [token errors proofs row-fragments]}] frozen-s5-surface]
        (testing (str k)
          (let [row (table-row-for lines token)]
            (is (some? row)
                (str "the profile must catalogue " token " in exactly one table row"))
            (when row
              (doseq [e errors]
                (is (str/includes? row (str e))
                    (str token "'s row must name its loud failure " e)))
              (doseq [p proofs]
                (is (str/includes? row p)
                    (str token "'s row must name its proof home " p)))
              (doseq [f row-fragments]
                (is (str/includes? row f)
                    (str token "'s row must carry its contract fragment: " f))))))))))

(deftest profile-names-real-proof-homes
  ;; A proof home that is not a real test namespace is a lie the row-binding
  ;; checks above cannot see — they only prove the STRING is in the row. The S5
  ;; proofs live under implementation/ssr/test (the seam consumers), a few
  ;; helpers under implementation/ui/test; check both trees.
  (let [roots  [(io/file (repo-root) "implementation" "ssr" "test")
                (io/file (repo-root) "implementation" "ui" "test")]
        exists? (fn [ns-name]
                  (let [base (-> ns-name (str/replace "-" "_") (str/replace "." "/"))]
                    (some (fn [root]
                            (some #(.exists (io/file root (str base %)))
                                  [".clj" ".cljs" ".cljc"]))
                          roots)))]
    (doseq [home (mapcat :proofs (vals frozen-s5-surface))]
      (is (exists? home)
          (str "the profile names proof home " home
               ", which is not a test namespace under implementation/{ssr,ui}/test")))))

;; ===========================================================================
;; MERGE PRECONDITION (fail-closed) — rf2-vxgfnd.97.3. Every blessed S5 facade
;; name must have a real implementation site. THIS IS THE GATE'S TOOTH: a
;; claimed-but-absent S5 public name reds this test, so the gate — whose merge
;; is the ruled S6 entry token — cannot pass on a phantom surface.
;; ===========================================================================

(deftest merge-precondition-blessed-s5-facade-names-resolve
  (testing "every blessed S5 public name resolves to a real re-frame.ui var — a claimed-but-absent name is RED BY DESIGN (the S5 audit's names-without-implementations failure mode)"
    (doseq [sym blessed-s5-facade-names]
      (is (some? (ns-resolve 're-frame.ui sym))
          (str "MERGE PRECONDITION (fail-closed, rf2-vxgfnd.97.3): blessed S5 "
               "public name re-frame.ui/" sym " has NO implementation site — the "
               "gate MUST NOT go green / merge (its merge is the S6 entry token, "
               "rf2-vxgfnd.98) until " sym " ships with its named proof green. "
               "This red is BY DESIGN, not a broken build: it is the gate "
               "catching the names-without-implementations failure mode the S5 "
               "epic's own audit caught. See §3 of "
               "spec/conformance/S5-view-conformance-profile.md.")))))

;; ===========================================================================
;; §3 render-static honesty + §6 grading — the profile must NOT over-claim while
;; render-static is a phantom. These bind the honest treatment so a future edit
;; cannot silently flip the profile to "conforming" over an absent surface.
;; ===========================================================================

(deftest profile-rows-render-static-as-outstanding-not-conforming
  (testing "§3 rows render-static as blessed-but-unimplemented — NOT among the §1 frozen rows"
    ;; render-static must NOT appear as a §1 conforming table row (that would be
    ;; the false-green — a conforming claim over an absent surface).
    (is (nil? (table-row-for (doc-lines) "`render-static`"))
        "render-static must not be a §1 conforming table row while it is unimplemented")
    (let [section (section-between (doc-text) "## 3." "## 4.")]
      (is (some? section) "the profile must have a §3 render-static section and a §4 header")
      (when section
        (doseq [claim ["render-static"
                       "has not shipped"
                       "no implementation"
                       "names-without-implementations"
                       "fail-closed"
                       "kind-label delta"
                       "`fn`"]]
          (is (str/includes? section claim)
              (str "§3 must state: " claim)))))))

(deftest profile-declines-conformance-while-render-static-absent
  (testing "§6 does NOT declare S5 conforming while render-static is a phantom"
    (let [text (doc-text)
          tail (subs text (str/index-of text "## 6."))]
      (is (str/includes? tail "S5 IS NOT YET DECLARED CONFORMING")
          "§6 must state S5 IS NOT YET DECLARED CONFORMING")
      (is (str/includes? tail "red by design")
          "§6 must state the drift guard is red by design")
      (is (str/includes? tail "rf2-vxgfnd.98")
          "§6 must name the ruled S6 entry token")
      ;; the affirmative false claim must be absent everywhere
      (is (not (str/includes? text "S5 IS DECLARED CONFORMING"))
          "the profile must not falsely declare S5 conforming while render-static is absent"))))

;; ===========================================================================
;; The non-surface wall (§4) and the S5 gate roster (§5).
;; ===========================================================================

(deftest profile-documents-the-non-surface-wall
  (testing "§4 enumerates the explicit S5 non-surfaces (the wall)"
    (let [section (section-between (doc-text) "## 4." "## 5.")]
      (is (some? section) "the profile must have a §4 wall and a §5 header")
      (when section
        (doseq [claim ["no version-negotiation protocol"
                       "no mandatory extension key"
                       "no page-wide hydration barrier"
                       "no per-site phase state"
                       "no second server product"
                       "no `ui → ssr` static require"
                       "no RSC and no streaming sugar"]]
          (is (str/includes? section claim)
              (str "the §4 wall must enumerate: " claim)))))))

(deftest profile-gate-roster-names-the-s5-arms
  (testing "§5 discharges the residual named gate and grows G-7; it adds no new G-numbered gate"
    (let [section (section-between (doc-text) "## 5." "## 6.")]
      (is (some? section) "the profile must have a §5 gate roster and a §6 header")
      (when section
        (doseq [claim ["root-manifest hydration + failed-root isolation"
                       "residual named gate"
                       "no new dedicated CI job"
                       "no new G-numbered gate"
                       "root-hydration half of W14"
                       "**G-7**"]]
          (is (str/includes? section claim)
              (str "§5 must state: " claim)))))))
