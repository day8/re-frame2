(ns re-frame.ssr-doc-example-node-build-id-test
  "rf2-8arzr.6 ACCEPTANCE — the \"Render on Node\" recipe in
  `docs/ssr/concepts.md` writes ONE build id in THREE places, and this
  suite fails when they diverge.

  WHY THIS SUITE EXISTS. The recipe is a paste-and-run deployment
  procedure, and the sidecar checks build skew in both directions: the
  service refuses a request whose `buildId` is not the loaded module's,
  and the adapter refuses an answer whose `x-rf-ssr-build` is not the
  `:build-id` it was configured with (`:rf.error/ssr-node-build-skew`).
  That check is exactly why the page leaves the bundle's `goog-define`
  and the adapter's `:build-id` at the same development default, and the
  page says so in its own words — \"the two literals above are one value
  written twice\". A reader who pastes both fences gets a page; a reader
  who pastes a recipe whose two literals have drifted apart gets a
  refusal on EVERY request, and the refusal names the skew rather than
  the edit that caused it.

  Nothing was checking that. `mkdocs build --strict` renders the page and
  `scripts/check_doc_slugs.py` validates its links and anchors; neither
  reads inside a fence, let alone compares two of them. So the one defect
  this recipe can carry that breaks every request for the reader was
  invisible to every gate the page had — the same blind spot
  `re-frame.ssr-doc-example-projector-test` and
  `re-frame.ssr-doc-example-form-action-test` were written for, reached
  through a different kind of drift: theirs is code that stops working,
  this one is two literals that stop agreeing.

  WHY THREE PLACES AND NOT TWO. The page also prints the launcher's
  `ready` line, and its `buildId` is not a fourth decision — it is the
  sidecar reporting the id of the module it loaded, so it is the SAME
  value a third time. An edit that updates the two fences and leaves the
  sample output behind publishes a transcript that no run of the recipe
  can produce.

  WHAT IS DELIBERATELY NOT PINNED. The value. `\"my-app-dev\"` is the
  page's development default and the page is free to change it; the
  invariant is AGREEMENT, not the string, so a rename that moves all
  three together stays green. Each fence is located by a needle that must
  match exactly one block, so a rewrite that removes a step fails loudly
  here rather than silently pinning nothing.

  POSTURE-INDEPENDENT. Every assertion is text read off a Markdown file:
  no frame, no dispatch, no trace bus, no `debug-enabled?` read. The
  namespace therefore executes identically under
  `scripts/test-ssr-prod-gate.sh`'s real `-Dre-frame.debug=false` gate and
  in the ordinary lane."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]))

;; ---------------------------------------------------------------------------
;; Reading the recipe out of the page
;; ---------------------------------------------------------------------------

(def ^:private concepts-page
  "`docs/ssr/concepts.md`, anchored to a CLASSPATH RESOURCE rather than the
  working directory (rf2-ywrwkl; the same anchoring
  `re-frame.ssr-doc-example-projector-test` uses for the API page). A
  `(io/file \"../../docs/...\")` form would resolve correctly under the
  per-artefact gate run from `implementation/ssr/` and silently MIS-SCOPE
  under the combined `implementation/deps.edn :test` alias. This namespace's
  own source is on the test classpath, so five parents
  (`…_test.clj → re_frame → test → ssr → implementation → repo root`) reach
  the repo root from wherever the JVM was started."
  (let [res (io/resource "re_frame/ssr_doc_example_node_build_id_test.clj")]
    (assert res
            (str "ssr-doc-example-node-build-id-test cannot locate its own "
                 "source on the classpath — the ssr test/ dir must be on the "
                 "test classpath for the concepts page to be found."))
    (-> (io/file res)  ; …/ssr/test/re_frame/ssr_doc_example_node_build_id_test.clj
        .getParentFile .getParentFile .getParentFile .getParentFile .getParentFile
        (io/file "docs" "ssr" "concepts.md")
        .getCanonicalFile)))

(defn- fence
  "The text of the one ```<lang> fence on the concepts page containing
  `needle`. Hard-errors on zero or many matches, so a rewritten page fails
  loudly rather than silently pinning nothing.

  Line endings are normalised first: the page is stored LF but checks out
  CRLF on a Windows dev box (`core.autocrlf`), and a `\\n`-only fence regex
  matches zero blocks there while passing on the Linux CI runner."
  [lang needle]
  (let [md   (str/replace (slurp concepts-page) "\r\n" "\n")
        hits (->> (re-seq (re-pattern (str "(?s)```" lang "\n(.*?)```")) md)
                  (map second)
                  (filter #(str/includes? % needle)))]
    (assert (= 1 (count hits))
            (str "expected EXACTLY ONE ```" lang " fence in " concepts-page
                 " containing " (pr-str needle) ", found " (count hits)
                 " — the pin has lost its anchor."))
    (first hits)))

(defn- only-capture
  "The one capture group `re` finds in `text`. Hard-errors on zero or many,
  for the same reason `fence` does: an ambiguous anchor pins nothing, and a
  vanished one must say so rather than compare `nil` to `nil`."
  [re what text]
  (let [hits (map second (re-seq re text))]
    (assert (= 1 (count hits))
            (str "expected EXACTLY ONE " what " in the fence, found "
                 (count hits) " — the pin has lost its anchor."))
    (first hits)))

(def ^:private bundle-build-id
  "Step 2's server bundle: `(goog-define build-id \"…\")`. This is the id the
  compiled module publishes as its `:buildId`, and the one the sidecar
  answers with in `x-rf-ssr-build`."
  (delay
    (only-capture #"\(goog-define\s+build-id\s+\"([^\"]*)\"\)"
                  "(goog-define build-id \"…\") form"
                  (fence "clojure" "(goog-define build-id"))))

(def ^:private adapter-build-id
  "Step 3's JVM host: the `:build-id` opt handed to `node/renderer`. The
  adapter refuses any answer whose `x-rf-ssr-build` is not this string."
  (delay
    (only-capture #":build-id\s+\"([^\"]*)\""
                  ":build-id \"…\" renderer opt"
                  (fence "clojure" "node/renderer"))))

(def ^:private ready-line-build-id
  "Step 5's launcher transcript: the `buildId` on the one `ready` line the
  sidecar writes to stdout. Reported BY the service ABOUT the module it
  loaded, so it is the bundle's id observed from outside."
  (delay
    (only-capture #"\"buildId\"\s*:\s*\"([^\"]*)\""
                  "\"buildId\" field on the ready line"
                  (fence "json" "\"rf.ssr-node\":\"ready\""))))

;; ===========================================================================
;; (1) THE GUARD: the two paired literals are one value written twice
;; ===========================================================================

(deftest the-paired-build-id-literals-do-not-diverge
  (testing "rf2-8arzr.6: the recipe's server bundle and its JVM host name the
            same build id. They are checked against each other at RUN time by
            the sidecar and the adapter, in both directions, so a page whose
            two literals disagree is a recipe that refuses every request the
            moment it is pasted — and the refusal names the skew rather than
            the edit. The page states this rule itself; this is the assertion
            that holds it."
    (is (seq @bundle-build-id)
        "step 2 still declares a non-empty build id on the server bundle")
    (is (seq @adapter-build-id)
        "step 3 still hands the renderer a non-empty :build-id")
    (is (= @bundle-build-id @adapter-build-id)
        (str "the recipe's build-id literals have DIVERGED: the server "
             "bundle's `goog-define build-id` is " (pr-str @bundle-build-id)
             " and the host's `:build-id` renderer opt is "
             (pr-str @adapter-build-id) ". A reader who pastes both fences "
             "gets :rf.error/ssr-node-build-skew on every request. They are "
             "one value written twice — change both or neither."))))

;; ===========================================================================
;; (2) The printed launcher transcript is one a run of the recipe produces
;; ===========================================================================

(deftest the-ready-line-transcript-reports-the-bundles-build-id
  (testing "rf2-8arzr.6: the sample `ready` line is the sidecar reporting the
            loaded module's own id, not a third independent choice. An edit
            that moves the two fences and leaves the transcript behind
            publishes output no run of this recipe can produce — and the
            transcript is precisely what an operator greps for to confirm the
            right bundle is serving."
    (is (= @bundle-build-id @ready-line-build-id)
        (str "the printed ready line reports "
             (pr-str @ready-line-build-id) " while the server bundle "
             "declares " (pr-str @bundle-build-id)
             " — the sample output is stale."))))
