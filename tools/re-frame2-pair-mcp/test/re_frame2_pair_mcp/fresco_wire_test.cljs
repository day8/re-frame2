(ns re-frame2-pair-mcp.hicasso-wire-test
  "THE BOTH-SIDES WITNESS. Pair's coupling to the evidence provider is a
  STRING — `re-frame.hicasso.tool`, interpolated into the CLJS source every
  view-evidence tool sends over nREPL — and a string is invisible to every
  static tool this repo runs. There is no `:require`, no `deps.edn`
  coordinate, and therefore no compiler error, no clj-kondo finding and no
  classpath scan that can see it. A dependency audit reports Pair CLEAN of the
  provider while every one of these tools calls it.

  That is precisely the shape that lets a rename pass review. Move or rename a
  read on the provider and Pair still COMPILES, its own suite still PASSES —
  the emitter is unit-tested against itself — and the failure appears only at
  runtime, in someone else's process, across the nREPL boundary. Under the
  donor door this repo carried that exposure for the whole life of the
  five-tool family: not one test in this tree had ever seen both sides.

  So this suite reads the PROVIDER'S OWN SOURCE and asserts the three halves of
  the wire contract against it:

    1. every reader fn named in an ACTUAL EMITTED FORM is defined, publicly, in
       `re-frame.hicasso.tool`;
    2. the consumer-owned `consumed-evidence-schema` equals the literal
       `re-frame.hicasso.evidence/schema` stamps on every envelope — the gate
       is worthless if the two drift, because every read then reports a
       mismatch and no read ever succeeds;
    3. no donor namespace survives anywhere in Pair's shipped source, so the
       migration cannot be quietly re-acquired by a copied form.

  **The names are extracted from the emitted form, not from a list.** Asserting
  a hand-written vector against the provider would prove only that two lists
  agree; parsing the string Pair will actually send is what makes this a
  witness to the wire rather than to a constant.

  Reading another artefact's source from a test is unusual, and it is the right
  shape here for the reason above: a classpath require is not available (the
  provider is a React substrate this Node suite cannot load, and Pair must have
  no production dependency on it at all), and a checker that could not see both
  sides would restate the gap instead of closing it."
  (:require [cljs.test :refer-macros [deftest is testing]]
            [clojure.string :as str]
            [re-frame2-pair-mcp.tools.hicasso-tool :as hicasso-tool]))

(def ^:private fs (js/require "fs"))
(def ^:private path (js/require "path"))

(defn- repo-root
  "Walk upward from the test process's cwd to the repository root — the first
  directory holding both this artefact and the provider's tree. Robust against
  the runner's working directory (`tools/re-frame2-pair-mcp` vs repo root)."
  []
  (loop [d (.cwd js/process)]
    (cond
      (and (.existsSync fs (.join path d "tools/re-frame2-pair-mcp/src"))
           (.existsSync fs (.join path d "implementation/hicasso/src")))
      d

      (= d (.dirname path d))
      (throw (ex-info (str "Could not locate the repository root from cwd — the wire "
                           "witness needs both tools/re-frame2-pair-mcp/src and "
                           "implementation/hicasso/src to compare the two sides.")
                      {:cwd (.cwd js/process)}))

      :else (recur (.dirname path d)))))

(defn- read-text
  "A file's text with CR stripped. The assertions below are anchored on line
  starts, and a checkout with CRLF endings would otherwise fail every one of
  them for a reason that has nothing to do with the wire."
  [full]
  (str/replace (.toString (.readFileSync fs full)) "\r" ""))

(defn- slurp-repo [rel]
  (let [full (.join path (repo-root) rel)]
    (when-not (.existsSync fs full)
      (throw (ex-info (str "the provider source is missing: " rel
                           " — Pair's wire targets it by string, so its absence is "
                           "the failure this witness exists to report.")
                      {:path full})))
    (read-text full)))

(def ^:private provider-tool-src
  (delay (slurp-repo "implementation/hicasso/src/re_frame/hicasso/tool.cljs")))

(def ^:private provider-evidence-src
  (delay (slurp-repo "implementation/hicasso/src/re_frame/hicasso/evidence.cljs")))

(defn- emitted-read-names
  "Every read name a form Pair will actually send resolves off the door, as a
  set.

  Since rf2-t2ec the emitted form carries no `re-frame.hicasso.tool/<read>`
  SYMBOL — a var reference into a namespace the running build has not loaded is
  rejected by shadow's analyzer before the form can run, which is what made the
  `:evidence-tier-unavailable` rung unreachable. The door is now resolved at
  runtime, so the read name rides as the string handed to `cljs.core/munge`,
  and that is the occurrence this parses. Still the EMITTED form rather than a
  list: what is asserted against the provider is what Pair will send."
  [form]
  (into #{}
        (map second)
        (re-seq #"\(cljs\.core/munge \"([^\"]+)\"\)" form)))

(defn- emitted-door-namespaces
  "Every namespace name the form asks `cljs.core/find-ns-obj` to resolve. The
  other half of the wire coupling, and the half that decides whether the
  absent-door rung is reached at all."
  [form]
  (into #{}
        (map second)
        (re-seq #"\(cljs\.core/find-ns-obj \"([^\"]+)\"\)" form)))

;; ---------------------------------------------------------------------------
;; 1. Every read the emitter names is published by the provider
;; ---------------------------------------------------------------------------

(deftest every-emitted-read-is-defined-by-the-provider
  (let [src @provider-tool-src]
    (doseq [read-fn hicasso-tool/tier-reads]
      (testing read-fn
        (let [form  (hicasso-tool/projection-form read-fn)
              named (emitted-read-names form)]
          (is (= #{read-fn} named)
              "the emitted form names this read and no other")
          (is (= #{hicasso-tool/tier-ns} (emitted-door-namespaces form))
              "…resolved off the door namespace and no other")
          ;; A public `defn` at column 0. `defn-` would not match, and must
          ;; not: a private read is unreachable from an eval form even though
          ;; the name is spelled identically.
          (is (str/includes? src (str "\n(defn " read-fn "\n"))
              (str "re-frame.hicasso.tool must publish " read-fn
                   " — the emitted form calls it by name across a process "
                   "boundary, so a rename on the provider is a runtime failure "
                   "here and nowhere else")))))))

(deftest the-emitter-names-nothing-the-provider-does-not-publish
  ;; The reverse direction, over the forms as a whole rather than per read: a
  ;; helper accidentally interpolating a second symbol would ship a call no
  ;; provider answers.
  (let [src   @provider-tool-src
        named (into #{}
                    (mapcat #(emitted-read-names (hicasso-tool/projection-form %)))
                    hicasso-tool/tier-reads)]
    (is (= (set hicasso-tool/tier-reads) named)
        "the emitted forms name exactly the declared reads")
    (doseq [n named]
      (is (str/includes? src (str "\n(defn " n "\n"))
          (str n " is emitted onto the wire but not published by the provider")))))

;; ---------------------------------------------------------------------------
;; 2. The schema gate matches what the producer actually stamps
;; ---------------------------------------------------------------------------

(deftest the-consumed-schema-is-the-schema-the-producer-stamps
  ;; `versioned-envelope-result` refuses any envelope whose `:schema` differs
  ;; from `consumed-evidence-schema`. If the producer's literal moves and this
  ;; one does not, the gate does not fail open — it fails CLOSED on every read,
  ;; and every tool in the family returns a mismatch forever. That is a silent
  ;; total outage, so it gets a witness rather than a comment.
  (let [src @provider-evidence-src
        m   (re-find #"\(def schema\b[\s\S]*?\n  (:re-frame\.hicasso\.evidence/v\d+)\)" src)]
    (is (some? m)
        "re-frame.hicasso.evidence/schema must carry a keyword literal this witness can read")
    (is (= (str hicasso-tool/consumed-evidence-schema) (second m))
        (str "Pair consumes " hicasso-tool/consumed-evidence-schema
             " but the producer stamps " (second m)
             " — bump consumed-evidence-schema ONLY once this build is taught "
             "the new shape"))))

;; ---------------------------------------------------------------------------
;; 3. The donor wire string is gone, and cannot be re-acquired quietly
;; ---------------------------------------------------------------------------

(defn- cljs-sources-under
  [dir]
  (mapcat (fn [entry]
            (let [full (.join path dir entry)]
              (cond
                (.isDirectory (.statSync fs full)) (cljs-sources-under full)
                (str/ends-with? entry ".cljs")     [full]
                :else                              [])))
          (.readdirSync fs dir)))

(deftest no-donor-namespace-survives-in-pairs-shipped-source
  ;; rf2-n3mb. The donor coupling was never a `:require`, so nothing in the
  ;; build could have told us it was there. This is the grep that can only be
  ;; trusted because the surface it scans is small and wholly ours.
  (let [src-root (.join path (repo-root) "tools/re-frame2-pair-mcp/src")
        files    (cljs-sources-under src-root)]
    (is (seq files) "the scan found source files to check")
    (doseq [f files]
      (let [text (read-text f)]
        (doseq [donor ["re-frame.freehand" "re-frame.ui.tool"]]
          ;; Prose may NAME the donor to record that it was left behind — the
          ;; hicasso-tool docstring does exactly that. What must not appear is
          ;; a donor symbol in a position that reaches the wire.
          (is (not (str/includes? text (str donor "/")))
              (str (.basename path f) " names a donor read (" donor
                   "/…) in a callable position")))))))

(deftest the-tier-string-is-the-adapter-neutral-door
  (is (= "re-frame.hicasso.tool" hicasso-tool/tier-ns)
      "the wire targets the adapter-neutral provider, not a donor tier"))
