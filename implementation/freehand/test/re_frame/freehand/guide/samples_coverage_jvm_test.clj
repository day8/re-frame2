(ns re-frame.freehand.guide.samples-coverage-jvm-test
  "The ONE coverage check that keeps `docs/core/freehand/` and the guide
  fixtures beside it honest.

  ## Why this exists

  The Freehand guide is written against a door that is deliberately NOT
  frozen. Every sample it prints is a claim about a spelling — `v/sub`,
  `::v/value`, `v/spread-safe`, `t/with-render` — and prose cannot notice
  when one of those moves. The fixture namespaces under
  `re-frame.freehand.guide.*` fix that half: they transcribe the samples
  onto the real classpath, so a renamed verb is a compile failure.

  This namespace fixes the OTHER half, which fixtures alone cannot: the
  relationship between a page and the fixture that proves it. Without it,
  a sample added to the guide is covered by nothing and nobody notices, and
  a sample EDITED in the guide leaves its fixture proving the old spelling
  while the page teaches the new one. Both are silent, and both are exactly
  the staleness rf2-qwsmv exists to end.

  ## The rule, and why it is this one

  **Every fenced block in `docs/core/freehand/` carries a roster row naming
  either the fixture var that proves it or the reason it cannot be run, and
  the row pins a digest of the block's exact text.**

  Three consequences, which are the whole point:

  - **Adding a sample reds.** A new block has no row, so the roster and the
    corpus disagree and the run fails naming the page and the ordinal. The
    author must either point it at a fixture or state a reason.
  - **Editing a sample reds.** The digest is over the block's text, so any
    change to it — code or the `;; =>` commentary that states an expected
    value — forces a look at the fixture that claims to prove it. The
    digest is deliberately NOT normalised past line endings and trailing
    whitespace: a comment inside a sample often carries the claim (see
    `events-and-handlers.md`'s projection roster), and a rule that ignored
    comments would ignore those.
  - **Deleting a fixture reds.** Each row's symbol is resolved, so a
    fixture var that is renamed or removed fails here even when nothing
    else references it.

  The check is deliberately NOT a markdown interpreter. It does not parse,
  evaluate, or understand a single line of Clojure in the guide — it splits
  on fences, hashes bytes, and compares two sets. Executing a sample is the
  fixtures' job, and this namespace never tries to do it for them. That
  boundary is what keeps the mechanism cheap enough to be trusted.

  ## The unexecutable classes

  A sample earns a reason keyword only from [[unexecutable-reasons]], which
  is closed. Anything genuinely runnable belongs to a fixture, and the
  reasons are for the classes that have no JVM or node counterpart at all:
  a CSS or ASCII-diagram fence, an illustrative data shape, a consumer's
  `ns` form naming an adapter artefact this one does not depend on, a boot
  function that needs `js/document`, an npm import, or a `deps.edn` /
  `shadow-cljs.edn` fragment.

  ## Extending the guide

  Add the page's block, run this suite, and paste the row it prints. That is
  the whole workflow, and it is one line per sample.

  Filed under rf2-qwsmv."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]])
  (:import [java.security MessageDigest]))

;; ---------------------------------------------------------------------------
;; Locating the corpus
;; ---------------------------------------------------------------------------

(defn- repo-root
  "The repository root, found by walking up for the `AGENTS.md` marker
  rather than by a hardcoded relative path — this suite runs from
  `implementation/freehand` locally and from the repo root in CI."
  []
  (or (some (fn [candidate]
              (let [root (.getCanonicalFile (io/file candidate))]
                (when (.isFile (io/file root "AGENTS.md"))
                  root)))
            (take 6 (iterate #(io/file % "..") (io/file "."))))
      (throw (ex-info "Could not locate repository root" {}))))

(def ^:private guide-dir "docs/core/freehand")

;; ---------------------------------------------------------------------------
;; Splitting a page into blocks, and digesting one
;; ---------------------------------------------------------------------------

(defn- sha12
  "The first 12 hex digits of the SHA-256 of `s` — short enough to read in
  a diff, long enough that a collision is not a thing that happens."
  [^String s]
  (let [d (.digest (MessageDigest/getInstance "SHA-256")
                   (.getBytes s "UTF-8"))]
    (apply str (map #(format "%02x" %) (take 6 d)))))

(defn- normalise
  "Line endings and trailing whitespace only. A `.md` checked out with CRLF
  on Windows and LF on Linux is the same sample, and an editor stripping a
  trailing space did not change what the page teaches. NOTHING else is
  normalised — see the rule in this namespace's docstring."
  [lines]
  (->> lines
       (map str/trimr)
       (reverse)
       (drop-while str/blank?)
       (reverse)
       (str/join "\n")))

(defn blocks
  "Every fenced block in `text`, in document order, as
  `[{:n ordinal :digest … :lang … :first-line …}]`.

  A fence is a line opening with three backticks; the block runs to the
  next such line. That is the whole parser."
  [text]
  (let [lines (vec (str/split (str/replace text "\r\n" "\n") #"\n" -1))
        fence? #(str/starts-with? ^String % "```")]
    (loop [i 0, n 0, acc []]
      (cond
        (>= i (count lines)) acc

        (not (fence? (nth lines i))) (recur (inc i) n acc)

        :else
        (let [lang  (str/trim (subs (nth lines i) 3))
              close (or (first (keep-indexed #(when (fence? %2) (+ i 1 %1))
                                             (subvec lines (inc i))))
                        (count lines))
              body  (subvec lines (inc i) close)]
          (recur (inc close)
                 (inc n)
                 (conj acc {:n          (inc n)
                            :lang       (if (str/blank? lang) "none" lang)
                            :digest     (sha12 (normalise body))
                            :first-line (first (remove str/blank? body))})))))))

(defn corpus
  "`{page-filename [block …]}` for every `.md` page in the guide."
  []
  (let [dir (io/file (repo-root) guide-dir)]
    (into (sorted-map)
          (for [^java.io.File f (sort-by #(.getName ^java.io.File %) (.listFiles dir))
                :when (str/ends-with? (.getName f) ".md")]
            [(.getName f) (blocks (slurp f))]))))

;; ---------------------------------------------------------------------------
;; What a row may say
;; ---------------------------------------------------------------------------

(def ^:private fixture-namespaces
  "The chapter fixture namespaces, by the short alias the roster uses. This
  map is also the CLOSED set of namespaces a row may name: a roster row
  pointing anywhere else is rejected, so coverage cannot quietly be claimed
  by some unrelated suite that happens to mention a verb."
  '{first-view  re-frame.freehand.guide.first-view-cljs-test
    composition re-frame.freehand.guide.composition-cljs-test
    events      re-frame.freehand.guide.events-cljs-test
    controllers re-frame.freehand.guide.controllers-cljs-test
    compilation re-frame.freehand.guide.compilation-cljs-test
    host        re-frame.freehand.guide.host-cljs-test
    testing     re-frame.freehand.guide.testing-cljs-test})

(def ^:private unexecutable-reasons
  "The CLOSED set of reasons a fenced block may carry instead of a fixture.
  Each names a class with no JVM or node counterpart — not a class that is
  merely inconvenient, which is what keeps the set from growing into an
  excuse column."
  {:non-clojure   "a CSS or ASCII-diagram fence — not Clojure at all"
   :prose-shape   "an illustrative data shape (an app-db slice, a build
                   diagnostic) with no verb in it to move"
   :app-scaffold  "a consumer `ns` form, or a full application listing whose
                   parts each already have a fixture — a page-level scaffold
                   with no single var for a row to point at"
   :host-boot     "a boot function needing a real `js/document` container"
   :foreign-npm   "an `ns` requiring an npm module that is on no classpath
                   here"
   :build-config  "a `deps.edn` / `shadow-cljs.edn` fragment"})

;; ---------------------------------------------------------------------------
;; Comparing the roster against the corpus
;; ---------------------------------------------------------------------------

(defn- row-str
  [page [n digest by]]
  (format "%s block %d  →  [%2d \"%s\" %s]" page n n digest by))

(defn roster-drift
  "The pure comparison, so it can be fed synthetic input by
  [[the-check-has-teeth]]. Answers a map of problem-kind → seq of
  human-readable lines; an empty map means the roster and the corpus agree.

  Kept total and data-in/data-out deliberately: a gate whose own logic
  cannot be exercised is a gate nobody can trust to fail."
  [corpus roster]
  (let [pages    (set (concat (keys corpus) (keys roster)))
        problems (for [page (sort pages)
                       :let [found (vec (get corpus page))
                             rows  (vec (get roster page))]
                       problem
                       (concat
                         (when-not (contains? corpus page)
                           [[:unknown-page (str page " — the roster names a page that is not in "
                                                guide-dir)]])
                         (when-not (contains? roster page)
                           [[:unrostered-page (str page " — a guide page with no roster entry")]])
                         (when (and (contains? corpus page) (contains? roster page))
                           (concat
                             (when (not= (count found) (count rows))
                               [[:count (format "%s — the page has %d fenced blocks; the roster has %d rows"
                                                page (count found) (count rows))]])
                             (for [[block row] (map vector found rows)
                                   :when (not= (:digest block) (second row))]
                               [:digest (format "%s block %d has changed — paste [%2d \"%s\" %s] (was \"%s\"); first line: %s"
                                                page (:n block) (:n block) (:digest block)
                                                (nth row 2) (second row)
                                                (pr-str (:first-line block)))])
                             (for [block (drop (count rows) found)]
                               [:unowned (format "%s block %d has no roster row — first line: %s"
                                                 page (:n block) (pr-str (:first-line block)))])
                             (for [row (drop (count found) rows)]
                               [:stale (str "roster row for a block that is gone — "
                                            (row-str page row))]))))]
                   problem)]
    (reduce (fn [m [k line]] (update m k (fnil conj []) line)) {} problems)))

;; ---------------------------------------------------------------------------
;; The roster
;; ---------------------------------------------------------------------------
;;
;; `page → [[block-ordinal digest owner] …]`, block ordinals in document
;; order. `owner` is either `alias/var-name` — the fixture that proves the
;; block, resolved through `fixture-namespaces` — or one of
;; `unexecutable-reasons`.
;;
;; One block, one row, one owner. Several blocks may name the SAME fixture
;; var: the guide repeats its `toast-card` on three pages and its `panel` on
;; two, and a fixture that already proves the spelling proves it again.
;;
;; Where a block declares more than one var, the row names the one carrying
;; the block's claim — usually its first declaration. Where the block is a
;; bare markup fragment, the row names the one-line `v/defview` that hosts
;; it. Both conventions are stated in the fixture namespaces themselves.

(def ^:private roster
  '{
   "accessibility.md"
   [[ 1 "cf922cd60399" host/a11y-cart-buttons]
    [ 2 "168c79423b66" host/toast-card]]
   "adoption.md"
   [[ 1 "465c90a78f6e" host/person-cell-renderer-props]]
   "build-a-view.md"
   [[ 1 "f0c3d822dd93" :app-scaffold]
    [ 2 "1ffd888b3d3f" first-view/register-counter-dataflow!]
    [ 3 "d2d23ba805b8" first-view/counter-v1]
    [ 4 "c7ca85a039f7" first-view/counter-v2]
    [ 5 "e848116f474a" first-view/counter-v3]
    [ 6 "c7ecf21136ef" first-view/note-row]
    [ 7 "54f01ca77513" :host-boot]
    [ 8 "6b6946d0b4bd" :app-scaffold]]
   "compilation.md"
   [[ 1 "2ed0def9d890" compilation/people-list-promotion-recipe]
    [ 2 "0e709b251a18" compilation/todo-row-compiled]
    [ 3 "441eba0cabf9" :prose-shape]
    [ 4 "baf0226b671c" compilation/people-list-interpreted]
    [ 5 "8ed7b69a0185" compilation/item-row]
    [ 6 "63ff086fcdd5" compilation/field-help]
    [ 7 "0eb044ab4a85" compilation/markup-child]
    [ 8 "6eca931fc482" compilation/data-table-compiled]
    [ 9 "ab5df8171160" compilation/todo-row-with-props-schema]]
   "composition.md"
   [[ 1 "6e08b04edd36" composition/panel]
    [ 2 "f1f241908c1f" composition/icon-button]
    [ 3 "04fc59833fc3" composition/card]
    [ 4 "1a6b78ff632e" composition/disclosure]
    [ 5 "80cf85b09bce" composition/data-table]
    [ 6 "2de09c0bef52" composition/person-row]
    [ 7 "f01963c823bb" composition/todo-list]
    [ 8 "b90b69b28756" composition/spread-forms]
    [ 9 "2e9534754389" composition/field]
    [10 "d885456ac680" composition/date-field]
    [11 "7ca003669260" :non-clojure]
    [12 "143ffc47edc9" composition/field]
    [13 "5741c8ca53dc" composition/field-call-with-parts]
    [14 "538ba8b3f20d" :prose-shape]]
   "debugging.md"
   [[ 1 "edafd4232c9e" compilation/describe-projects-exactly-the-keys-the-page-prints]
    [ 2 "1e6adbe055e5" compilation/manifest-is-nil-for-interpreted-and-names-its-crossings-when-compiled]
    [ 3 "6a368565f741" host/active-connections-read]
    [ 4 "69279406eee9" host/command-log-read]]
   "events-and-handlers.md"
   [[ 1 "603f633abe5a" events/add-to-cart-button]
    [ 2 "987032f4a387" events/projection-marker-sites]
    [ 3 "ce57d8c6132c" events/the-named-projection-roster-is-the-five-the-page-lists]
    [ 4 "6190439f02be" events/general-read-sites]
    [ 5 "32a862906cbe" events/submit-options-map]
    [ 6 "a23aaa2dace6" events/key-pressed-site]
    [ 7 "3a02de0cc196" events/key-branch-site]
    [ 8 "782b6e3d7e3a" events/controlled-email-field]
    [ 9 "61dc3b641bbb" events/live-domain-field]
    [10 "6f9f23aedc3a" events/draft-then-commit-field]
    [11 "73ff51badb9b" events/register-draft-key-handler!]
    [12 "59a361023b4c" events/uncontrolled-cell-input]
    [13 "f4c566df3500" events/icon-button]
    [14 "f1487c436d64" events/text-field]]
   "forms.md"
   [[ 1 "76b9d90f3f40" :prose-shape]
    [ 2 "b79d37ae76c4" events/email-field]
    [ 3 "a6379d0556c8" events/register-field-error-sub!]
    [ 4 "d6209ce60e2d" events/register-article-loaded!]
    [ 5 "9654384295ab" events/register-editor-submit!]
    [ 6 "5ec85f9349ea" events/editor-actions]
    [ 7 "cf8ffa56a3d3" events/register-temperature-dataflow!]
    [ 8 "67c6b1269513" events/temperature-inputs]
    [ 9 "a166f137df6a" :prose-shape]]
   "host-boundaries.md"
   [[ 1 "0f67952b0507" :foreign-npm]
    [ 2 "1fc6ecc0cd84" host/chart-client-only]
    [ 3 "3ec3907b4472" host/autosize]
    [ 4 "7fc18b64ff4c" host/register-refit-requested!]
    [ 5 "5f2b2ed14f93" host/person-cell-react]
    [ 6 "61e4193ba86b" host/top-layer-sites]
    [ 7 "df50f54e87dd" host/workspace-error-boundary]
    [ 8 "3ec49de3e0d3" host/autofocus-input]]
   "index.md"
   [[ 1 "c00fd3636ace" first-view/cart-badge]
    [ 2 "5eca60041a06" :non-clojure]]
   "install.md"
   [[ 1 "4812718ae9f1" :build-config]
    [ 2 "88af3aed1747" :app-scaffold]
    [ 3 "54ad15b06540" host/unmount-root!]
    [ 4 "0673f40834e8" host/mount-panels!]
    [ 5 "ea8536be30aa" :build-config]]
   "js-libraries.md"
   [[ 1 "c27c0a28d420" host/toast-tray]
    [ 2 "b9e89841da17" :foreign-npm]
    [ 3 "e94f3380438e" :foreign-npm]
    [ 4 "2248c34107cb" :foreign-npm]
    [ 5 "8e3bda770349" host/fade-panel]]
   "limits-and-escapes.md"
   []
   "mental-model.md"
   [[ 1 "207fd114bc23" first-view/greeting]
    [ 2 "0618d0abdbe2" first-view/greeting-call]
    [ 3 "3cf72f2f1362" first-view/invoice]
    [ 4 "a58a02b83260" compilation/todo-row-compiled]
    [ 5 "58778e69f919" first-view/count-span]
    [ 6 "2827bb2ccfc3" first-view/basket-total-once]
    [ 7 "2d2a7aa15596" first-view/inc-button]
    [ 8 "b6b64c99bd63" first-view/email-input]
    [ 9 "e69077c3dbb9" :host-boot]
    [10 "306c67de7ea3" host/person-cell-renderer-props]]
   "presence.md"
   [[ 1 "fcb73466d44d" :non-clojure]
    [ 2 "c27c0a28d420" host/toast-tray]
    [ 3 "80e6e0d3305d" :non-clojure]
    [ 4 "08ba0a5e6fa4" host/toast-card-case]
    [ 5 "168c79423b66" host/toast-card]]
   "reactivity-and-ownership.md"
   [[ 1 "f01963c823bb" composition/todo-list]
    [ 2 "1b29393aaea1" composition/people-page]]
   "semantic-controllers.md"
   [[ 1 "8bdf175ef483" controllers/keymap-field]
    [ 2 "977f3334fec6" controllers/disclosure]
    [ 3 "c56fcf222c65" controllers/register-search-dataflow!]
    [ 4 "c6d982c86ffd" controllers/search-box]
    [ 5 "f9f6f210d596" :non-clojure]
    [ 6 "c686e38abce7" :prose-shape]
    [ 7 "1d2a72ca0224" controllers/buffered-field-call]
    [ 8 "2f55fd8a5dfd" controllers/line-row-live]
    [ 9 "50ad6419c733" controllers/line-row-buffered]
    [10 "0465b1254454" controllers/the-controller-key-is-the-pair-of-kind-and-address]
    [11 "86f1f8b0a35b" controllers/the-controller-revision-is-the-callers-reset-key]
    [12 "6c7f63421374" controllers/the-generation-fence-is-total-and-safe-when-the-stamp-is-missing]
    [13 "dbe82da73aae" controllers/register-acme-buffered-text!]
    [14 "98155b34ebf3" controllers/buffered-field]]
   "ssr.md"
   [[ 1 "7d7c6d77a52d" host/chart-client-only-sub]
    [ 2 "b0412ac0fb48" :host-boot]
    [ 3 "dc23499554e7" host/mount-two-roots!]
    [ 4 "05f8d7b50eb1" :host-boot]
    [ 5 "2ae2d5c8a43a" host/article-route-link]]
   "state.md"
   [[ 1 "d904aba9124a" composition/order-summary]
    [ 2 "fc9c3bd71d12" composition/app-root]
    [ 3 "6e08b04edd36" composition/panel]
    [ 4 "3cc3502b4ebf" composition/email-controlled-input]
    [ 5 "096d667e0233" composition/email-draft-input]
    [ 6 "1d2a72ca0224" controllers/buffered-field-call]]
   "testing.md"
   [[ 1 "386f631342f6" testing/add-button-carries-intent]
    [ 2 "849437c5725e" testing/tree-reading-forms]
    [ 3 "c082e508ecc6" testing/the-badge-shows-the-basket-count]
    [ 4 "96e443410112" testing/cart-badge-shows-count-after-add]
    [ 5 "7d7d9c74abc1" testing/adding-to-the-cart-updates-the-badge]
    [ 6 "030cd0fec46c" testing/typing-carries-the-text]]
   "vs-reagent.md"
   [[ 1 "f97158f9fe1c" first-view/greeting]
    [ 2 "a087fe4506fc" first-view/count-span-bare]
    [ 3 "970add7a063f" first-view/inc-button]
    [ 4 "76bdaa9a72ca" first-view/email-input-vs-reagent]
    [ 5 "86baf68565fd" first-view/keyed-rows]
    [ 6 "3d8b88701b0e" first-view/counter-vs-reagent]]
  })

;; ---------------------------------------------------------------------------
;; The checks
;; ---------------------------------------------------------------------------

(deftest every-guide-sample-is-owned-and-unchanged
  (testing "each fenced block in docs/core/freehand/ carries a roster row,
            and the row's digest still matches the block's text. A failure
            here means the guide moved and its proof did not — paste the
            printed row after checking the fixture still proves the sample."
    (let [drift (roster-drift (corpus) roster)]
      (is (= {} drift)
          (str "\n" (str/join "\n" (mapcat val (sort-by key drift))) "\n")))))

(deftest every-owner-resolves-to-a-fixture-var-or-a-declared-reason
  (testing "a row names either a var that exists in one of the chapter
            fixture namespaces, or a reason from the closed set. A renamed
            or deleted fixture reds here even when nothing else refers to
            it."
    (let [bad (for [[page rows] roster
                    [n _ by]    rows
                    :let [problem
                          (cond
                            (keyword? by)
                            (when-not (contains? unexecutable-reasons by)
                              (str "reason " by " is not in the closed set "
                                   (pr-str (vec (sort (keys unexecutable-reasons))))))

                            (symbol? by)
                            (let [ns-alias (some-> (namespace by) symbol)
                                  target   (get fixture-namespaces ns-alias)]
                              (cond
                                (nil? ns-alias)
                                "owner symbol is not namespace-qualified"

                                (nil? target)
                                (str "unknown fixture alias " ns-alias " — the roster may name only "
                                     (pr-str (vec (sort (keys fixture-namespaces)))))

                                :else
                                (let [full (symbol (str target) (name by))
                                      seen (try {:var (requiring-resolve full)}
                                                (catch Exception e {:error (ex-message e)}))]
                                  (cond
                                    (:error seen) (str "loading " target " failed — " (:error seen))
                                    (nil? (:var seen)) (str "no var " full)))))

                            :else "owner is neither a symbol nor a keyword")]
                    :when problem]
                (format "%s block %d — %s" page n problem))]
      (is (= [] (vec bad)) (str "\n" (str/join "\n" bad) "\n")))))

(deftest the-check-has-teeth
  (testing "a gate that cannot fail is the staleness it was built to end.
            Every way this check is supposed to red is exercised against
            synthetic input, so a future simplification that quietly
            neuters it fails here first."
    (let [page   "x.md"
          block  (fn [n d] {:n n :digest d :first-line "(v/sub [:x])"})
          base   {page [(block 1 "aaaaaaaaaaaa") (block 2 "bbbbbbbbbbbb")]}
          rows   {page [[1 "aaaaaaaaaaaa" 'host/toast-card]
                        [2 "bbbbbbbbbbbb" :non-clojure]]}]
      (is (= {} (roster-drift base rows)) "agreement is silence")

      (testing "an EDITED sample is caught"
        (is (contains? (roster-drift (assoc base page [(block 1 "cccccccccccc")
                                                       (block 2 "bbbbbbbbbbbb")])
                                     rows)
                       :digest)))

      (testing "an ADDED sample with no row is caught"
        (let [drift (roster-drift (update base page conj (block 3 "dddddddddddd")) rows)]
          (is (contains? drift :unowned))
          (is (contains? drift :count))))

      (testing "a REMOVED sample leaves a stale row, and that is caught"
        (let [drift (roster-drift (assoc base page [(block 1 "aaaaaaaaaaaa")]) rows)]
          (is (contains? drift :stale))
          (is (contains? drift :count))))

      (testing "a WHOLE PAGE added to the guide is caught"
        (is (contains? (roster-drift (assoc base "new.md" [(block 1 "eeeeeeeeeeee")]) rows)
                       :unrostered-page)))

      (testing "a whole page REMOVED from the guide is caught"
        (is (contains? (roster-drift base (assoc rows "gone.md" [[1 "ffffffffffff" :non-clojure]]))
                       :unknown-page))))

    (testing "and the digest is content-sensitive in the way the rule claims"
      (let [d #(-> % vector normalise sha12)]
        (is (not= (d "(v/sub [:x])") (d "(v/subscribe [:x])"))
            "a moved verb changes the digest")
        (is (not= (d ";; => #{::v/value}") (d ";; => #{::v/text}"))
            "so does an expected value stated only in a comment")
        (is (= (d "(v/sub [:x])  ") (d "(v/sub [:x])"))
            "trailing whitespace does not")))))

(deftest the-coverage-numbers-are-reported
  (testing "not an assertion about the count — a census, printed so a
            reviewer can see at a glance how much of the guide is executed
            and which classes are deliberately not."
    (let [rows    (for [[page rows] roster, row rows] [page row])
          by      (map (comp last second) rows)
          covered (count (filter symbol? by))
          reasons (frequencies (filter keyword? by))]
      (println (format "\nFreehand guide sample coverage: %d of %d fenced blocks"
                       covered (count rows)))
      (doseq [[reason n] (sort-by key reasons)]
        (println (format "  %-14s %2d  %s" reason n
                         (str/replace (get unexecutable-reasons reason) #"\s+" " "))))
      (is (pos? covered) "the census found fixtures"))))
