(ns day8.re-frame2-template.repo-split-spec-test
  "Executable checks for the deps-new grammar documented by 005-Repo-Split.

   The spec documents one local-root override spelling (§2.1.2):

       day8/re-frame2-template%io.github.day8/re-frame2-template

   These tests guard the *token as written in the document*, not merely
   deps-new's parse of it. That distinction is the whole point: deps-new is a
   weak oracle for this token. A wrong repository half, and a tripled
   separator, both parse to the exact canonical `:template` symbol — so an
   assertion that only checks the parse stays green while the documented
   command is wrong in a way that bites at run time (a clone instead of the
   `:local/root` override; a stray deps-root). `override-token-defects`
   therefore inspects the token itself, and the mutation tests below pin that
   it rejects each class even where deps-new cannot."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [clojure.tools.deps.extensions.git :as git]
            [day8.re-frame2-template.test-support :refer [repo-root]]
            [org.corfield.new.impl :as deps-new.impl]))

(def ^:private canonical-token
  "The single override spelling 005-Repo-Split is permitted to document."
  "day8/re-frame2-template%io.github.day8/re-frame2-template")

(def ^:private canonical-template-sym
  "The template symbol the override must resolve to — the published coord."
  "io.github.day8/re-frame2-template")

(def ^:private local-repo-half
  "The repository half. Deliberately un-prefixed: no `io.github.*` means no
   `auto-git-url` match, so the `:local/root` override survives."
  "day8/re-frame2-template")

(def ^:private documented-token-floor
  "Occurrences of the token in 005-Repo-Split: the §2.1.2 canonical statement
   plus the §2.1 / §3.4.1 / §4 concrete examples. A floor rather than an exact
   count — adding a correct example is fine, losing one is not, and a floor
   still fails loudly if the extraction ever silently matches nothing."
  4)

(defn- repo-split-spec-text
  []
  (slurp (io/file (repo-root) "tools/template/spec/005-Repo-Split.md")))

(defn- documented-override-tokens
  "Every `%`-bearing re-frame2-template spelling in TEXT, extracted WHOLE.

   The match is a maximal run of token characters — deliberately permissive
   enough to swallow a wrong repository half, an extra separator, or a bare
   `%`-prefixed fragment. Permissive extraction plus an exact-equality
   assertion is what makes this guard real: a defective spelling is captured
   verbatim, with its own repository half, and then fails. Nothing here
   reconstructs, repairs, or supplies any part of the token.

   Bare grammar labels (`repo%template-sym`) are excluded by the
   re-frame2-template requirement; the pinned-tag coord
   (`io.github.day8/re-frame2-template#template-v…`) by the `%` requirement."
  [text]
  (->> (re-seq #"[A-Za-z0-9._/%-]+" text)
       (filter #(str/includes? % "%"))
       (filter #(str/includes? % "re-frame2-template"))))

(defn- override-token-defects
  "Reasons TOKEN is not the mandated two-component local-root override; empty
   = clean. Applied identically to the documented tokens and to the negative
   mutations, so a mutation deps-new happens to parse into the canonical
   `:template` is still rejected here."
  [token]
  (let [separators (count (filter #{\%} token))]
    (cond-> []
      (not= canonical-token token)
      (conj (str "not the exact mandated token (expected "
                 (pr-str canonical-token) ", got " (pr-str token) ")"))

      (not= 1 separators)
      (conj (str "must carry exactly one % separator, found " separators
                 " — a second separator promotes the template-sym to a deps-root"))

      (not (str/starts-with? token (str local-repo-half "%")))
      (conj (str "repository half must be " (pr-str local-repo-half)
                 " so auto-git-url cannot claim it")))))

;; --- the documented tokens -------------------------------------------------

(deftest documented-overrides-are-the-exact-token-test
  (testing "every %-bearing override spelling in the split spec is complete and exact"
    (let [tokens (documented-override-tokens (repo-split-spec-text))]
      (is (<= documented-token-floor (count tokens))
          (str "005-Repo-Split must keep at least " documented-token-floor
               " concrete override spellings under test; found " (count tokens)))
      (doseq [token tokens]
        (is (empty? (override-token-defects token))
            (str "documented override " (pr-str token) " is defective: "
                 (str/join "; " (override-token-defects token))))))))

(deftest documented-overrides-are-not-reconstructed-test
  (testing "the extractor reads whole tokens from the document, repository half included"
    ;; Guards the regression this suite was rebuilt around: the previous
    ;; helper matched only a `%…` suffix and prepended the expected repository
    ;; half, so a documented token with a wrong half could never fail. Feeding
    ;; the extractor a wrong half must surface that half verbatim.
    (let [wrong "io.github.day8/wrong-repo%io.github.day8/re-frame2-template"]
      (is (= [wrong] (documented-override-tokens (str "prose " wrong " prose")))
          "extraction must return the token as written, not a repaired one"))))

;; --- the exact token's behaviour under deps-new ----------------------------

(deftest canonical-token-preprocesses-to-published-coord-test
  (testing "the exact token drives deps-new's production option parser to the published coord"
    ;; No `with-redefs`: the local repository half cannot match auto-git-url,
    ;; so the real production path needs no git resolution and no network.
    ;; Stubbing here would hide exactly the property under test.
    (let [parsed (deps-new.impl/preprocess-options
                   {:template (symbol canonical-token)
                    :name     'acme/example})]
      (is (= canonical-template-sym (:template parsed))
          "the template-sym half must resolve to the canonical published coord")
      (is (nil? (:git-dir parsed))
          "a local-root override must not resolve a git checkout"))))

(deftest local-repo-half-bypasses-auto-git-url-test
  (testing "the repository half stays outside deps-new's auto-clone path"
    (is (nil? (git/auto-git-url (symbol local-repo-half)))
        (str local-repo-half " must not match auto-git-url, or the :local/root"
             " override would be lost to a clone"))
    (is (some? (git/auto-git-url (symbol canonical-template-sym)))
        (str canonical-template-sym " is expected to match auto-git-url — that is"
             " precisely why it cannot serve as the repository half"))))

(deftest canonical-template-sym-resolves-retargeted-body-test
  (testing "the template-sym half drives find-root to the io/github/day8 body"
    ;; §2.1's retarget claim, exercised against deps-new's real resolver.
    ;; The body is checked in at the pre-split `resources/day8/…` path, so this
    ;; materializes both layouts in a temp dir and pins that only the
    ;; retargeted one answers the published symbol.
    (let [tmp        (.toFile (java.nio.file.Files/createTempDirectory
                                "repo-split-find-root"
                                (make-array java.nio.file.attribute.FileAttribute 0)))
          retargeted (io/file tmp "retargeted/io/github/day8/re_frame2_template")
          pre-split  (io/file tmp "pre-split/day8/re_frame2_template")]
      (try
        (doseq [^java.io.File dir [retargeted pre-split]]
          (.mkdirs dir)
          (spit (io/file dir "template.edn") "{}"))
        (let [[dir edn] (deps-new.impl/find-root [(str (io/file tmp "retargeted"))]
                                                 (symbol canonical-template-sym))]
          (is (some? edn)
              "find-root must resolve the published symbol against the retargeted body")
          (is (= (.getCanonicalPath retargeted) dir)
              "the resolved body must be the io/github/day8 path"))
        (is (nil? (deps-new.impl/find-root [(str (io/file tmp "pre-split"))]
                                           (symbol canonical-template-sym)))
            "the pre-split day8/… layout must NOT answer the published symbol — this is
             the §2.1 retarget's regression guard")
        (finally
          (doseq [^java.io.File f (reverse (file-seq tmp))]
            (.delete f)))))))

;; --- the mutations the parse cannot catch ----------------------------------

(defn- parsed-template
  "deps-new's `:template` for TOKEN, with git resolution stubbed out so a
   defective repository half cannot reach the network."
  [token]
  (:template (with-redefs [deps-new.impl/get-git-sha (constantly [nil nil])]
               (deps-new.impl/preprocess-options
                 {:template (symbol token) :name 'acme/example}))))

(deftest wrong-repository-half-fails-the-guard-test
  (testing "a wrong repository half is rejected even though deps-new parses it identically"
    (let [mutation "io.github.day8/wrong-repo%io.github.day8/re-frame2-template"]
      (is (= canonical-template-sym (parsed-template mutation))
          "precondition: deps-new is blind here — the parse matches the canonical coord")
      (is (seq (override-token-defects mutation))
          "the guard must reject a wrong repository half on the token alone"))))

(deftest tripled-separator-fails-the-guard-test
  (testing "%%% is rejected even though deps-new parses it to the canonical coord"
    (let [mutation "day8/re-frame2-template%%%io.github.day8/re-frame2-template"]
      (is (= canonical-template-sym (parsed-template mutation))
          "precondition: deps-new is blind here — the extra field becomes a deps-root
           and :template still matches")
      (is (seq (override-token-defects mutation))
          "the guard must reject a tripled separator on the token alone"))))

(deftest doubled-separator-fails-the-guard-test
  (testing "%% is rejected by the guard (and, unlike the other classes, by deps-new too)"
    (let [mutation "day8/re-frame2-template%%io.github.day8/re-frame2-template"]
      (is (not= canonical-template-sym (parsed-template mutation))
          "deps-new mangles this one — it is the only class the parse catches")
      (is (seq (override-token-defects mutation))
          "the guard must reject a doubled separator on the token alone"))))

(deftest canonical-token-passes-the-guard-test
  (testing "the guard accepts the mandated spelling — it is strict, not vacuous"
    (is (empty? (override-token-defects canonical-token)))))

;; --- the grammar terminology ------------------------------------------------

(deftest split-spec-names-the-grammar-accurately-test
  (let [text (repo-split-spec-text)]
    (testing "the two-component grammar is named repo%template-sym"
      (is (str/includes? text "`repo%template-sym`")
          "005-Repo-Split must name the two-component override grammar"))
    (testing "the three-component deps-root grammar is named distinctly"
      (is (str/includes? text "`repo%deps-root%template-sym`")
          "the optional deps-root grammar must be named so the fields cannot be confused"))
    (testing "the mislabel repo%root%template-sym does not return"
      (is (not (str/includes? text "repo%root%template-sym"))
          "repo%root%template-sym conflates the two-component form with the
           three-component deps-root form — deps-new's two-component grammar has
           no root field"))))
