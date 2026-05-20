(ns re-frame.story.ui.markdown
  "Minimal CommonMark-subset markdown → hiccup renderer (rf2-wl7yr,
  audit C-2).

  Story's `:docs` mode pane (`re-frame.story.ui.docs/prose-section`)
  and `:prose`-layout workspaces previously rendered prose as
  `pre-wrap` plain text. Authors write prose in markdown — backticks,
  bullets, headings — and seeing the raw syntax is a perpetual
  papercut. This namespace lifts that text into hiccup so the prose
  pane reads as docs, not source.

  ## What we render

  A deliberate subset — Story v1 needs the common docs shapes, not
  full CommonMark / GFM:

  - `#`, `##`, `###` headings
  - paragraphs (separated by blank lines)
  - `**bold**` + `*italic*` + `_italic_`
  - inline `` `code` ``
  - fenced code blocks: ` ```lang ... ``` ` (or no lang)
  - bullet lists (`- ` or `* `)
  - numbered lists (`1. `, `2. `, ...)
  - `[label](url)` links
  - `> blockquote` lines (collapsed into one `<blockquote>` per run)
  - hard breaks via trailing two-space indent (CommonMark §6.7)

  ## What we deliberately do NOT render

  - HTML passthrough (security + we don't want raw HTML in prose)
  - Reference-style links (`[a][b]` + `[b]: url`)
  - Images (no `:src` attribute means no XSS surface)
  - Tables (the docs pane has its own tables; markdown tables would
    conflict with that authoring story)
  - Footnotes / definition lists / strikethrough (GFM extensions)
  - JSX / MDX (spec/008 §Out of scope at v1 line 209 — we're not
    pulling in a JSX parser; this stays pure-EDN markdown)

  ## Output

  Hiccup vector (`[:div.markdown ...]` wrapper containing the parsed
  block elements). Pure data → data so JVM tests can pin the parse.

  ## Performance

  Parser is a single-pass line walker — O(L) over the input lines.
  Re-parses on every render. The prose corpus is small (Story prose
  is typically tens of lines per workspace) so memoisation isn't
  worth the complexity at v1.

  ## Why inline rather than an npm dep

  The candidates (`markdown-it`, `marked`, `remark`) each carry
  several thousand lines + their own dep tree. Story already lives
  behind the `config/enabled?` elision gate, but pulling in even a
  small md parser would mean either (a) coupling Story's tools/
  artefact to an npm dep — bundle-isolation regressions — or (b)
  building a JVM-side reimplementation anyway. The inline parser
  here is ~150 lines of pure CLJC; it's simpler to maintain."
  (:require [clojure.string :as str]))

;; ---- inline-span tokenisation -------------------------------------------
;;
;; We walk a single line of text and emit a vector of `[hiccup-or-string ...]`
;; spans, recognising:
;;
;;   `code`            → [:code "code"]
;;   **bold**          → [:strong "bold"]
;;   *italic* _italic_ → [:em "italic"]
;;   [label](url)      → [:a {:href url ...} "label"]
;;
;; Naive left-to-right scan with regex anchors at the cursor — good
;; enough for our prose corpus, avoids the precedence headaches a full
;; CommonMark spans pass would invoke.

(defn- safe-url
  "Normalise a markdown link URL. Strips angle-brackets (CommonMark
  autolink syntax) and refuses anything that doesn't look like an
  http(s) / mailto / relative path. Returns a string safe to place in
  an `:href` slot — defends against `javascript:` URLs and friends."
  [raw]
  (let [s (-> raw (or "") str/trim
              (str/replace #"^<|>$" ""))]
    (cond
      (re-matches #"(?i)^(https?|mailto):.*" s) s
      (re-matches #"^[/#][^\s]*"              s) s
      (re-matches #"^[a-zA-Z0-9._\-/]+"       s) s
      :else                                      "#")))

(defn- index-of
  "Cross-target `indexOf` — JVM and CLJS both expose `.indexOf` on
  strings but their negative-return convention differs at the type
  level. Normalise to a Clojure-friendly `nil`-on-miss return."
  [^String s ^String needle ^long from]
  (let [n (count s)
        nlen (count needle)]
    (loop [i (long from)]
      (cond
        (> (+ i nlen) n) nil
        (= (subs s i (+ i nlen)) needle) i
        :else (recur (inc i))))))

(defn- parse-inline
  "Parse `s` (one line / paragraph chunk of text) into a sequence of
  hiccup vectors / strings. Pure data → data; JVM-testable.

  Cursor-walked single-pass scan with regex-free span recognition so
  the algorithm runs identically on JVM and CLJS."
  [s]
  (let [n (count s)]
    (loop [i   0
           acc []
           buf ""]
      (let [flush-buf
            (fn [acc' buf']
              (if (zero? (count buf'))
                acc'
                (conj acc' buf')))]
        (if (>= i n)
          (flush-buf acc buf)
          (let [c (nth s i)]
            (cond
              ;; inline code: `…`
              (and (= c \`)
                   (index-of s "`" (inc i)))
              (let [j (index-of s "`" (inc i))
                    code (subs s (inc i) j)]
                (recur (inc j)
                       (conj (flush-buf acc buf) [:code {} code])
                       ""))

              ;; bold: **…**
              (and (= c \*)
                   (< (inc i) n)
                   (= (nth s (inc i)) \*)
                   (index-of s "**" (+ i 2)))
              (let [j (index-of s "**" (+ i 2))
                    inner (subs s (+ i 2) j)]
                (recur (+ j 2)
                       (conj (flush-buf acc buf) (into [:strong {}] (parse-inline inner)))
                       ""))

              ;; italic: *…*  (single-star)
              (and (= c \*)
                   (index-of s "*" (inc i)))
              (let [j (index-of s "*" (inc i))
                    inner (subs s (inc i) j)]
                (recur (inc j)
                       (conj (flush-buf acc buf) (into [:em {}] (parse-inline inner)))
                       ""))

              ;; italic: _…_
              (and (= c \_)
                   (index-of s "_" (inc i)))
              (let [j (index-of s "_" (inc i))
                    inner (subs s (inc i) j)]
                (recur (inc j)
                       (conj (flush-buf acc buf) (into [:em {}] (parse-inline inner)))
                       ""))

              ;; link: [label](url)
              (and (= c \[)
                   (let [close-label (index-of s "]" (inc i))]
                     (and close-label
                          (< (inc close-label) n)
                          (= (nth s (inc close-label)) \()
                          (index-of s ")" (+ close-label 2)))))
              (let [close-label (index-of s "]" (inc i))
                    close-url   (index-of s ")" (+ close-label 2))
                    label       (subs s (inc i) close-label)
                    url         (subs s (+ close-label 2) close-url)]
                (recur (inc close-url)
                       (conj (flush-buf acc buf)
                             (into [:a {:href   (safe-url url)
                                        :target "_blank"
                                        :rel    "noopener noreferrer"}]
                                   (parse-inline label)))
                       ""))

              :else
              (recur (inc i) acc (str buf c)))))))))

;; ---- block-level parsing -----------------------------------------------

(defn- heading-level
  "If `line` starts with one to six `#` chars followed by space, return
  `[level rest]`. Otherwise nil."
  [line]
  (when-let [[_ hashes rest] (re-matches #"^(#{1,6})\s+(.*)$" line)]
    [(count hashes) rest]))

(defn- bullet-line?
  "Lines beginning with `- ` or `* ` (after optional indent) are bullet
  list items. Returns `[indent rest]` or nil."
  [line]
  (when-let [[_ indent rest] (re-matches #"^(\s*)[-*]\s+(.*)$" line)]
    [(count indent) rest]))

(defn- ordered-line?
  "Lines beginning with `<digit>+. ` are ordered list items. Returns
  `[indent rest]` or nil."
  [line]
  (when-let [[_ indent rest] (re-matches #"^(\s*)\d+\.\s+(.*)$" line)]
    [(count indent) rest]))

(defn- blockquote-line?
  "Lines beginning with `> ` are blockquote items."
  [line]
  (when-let [[_ rest] (re-matches #"^>\s?(.*)$" line)]
    rest))

(defn- fence-line?
  "Match a ``` fence opener / closer. Returns the lang (or empty string)
  on a fence line, nil otherwise."
  [line]
  (when-let [[_ lang] (re-matches #"^```\s*(\S*)\s*$" line)]
    lang))

(defn- blank?
  [line]
  (re-matches #"^\s*$" line))

(defn- soft-break-pieces
  "CommonMark: a trailing `  ` (two-space indent) on a line is a hard
  break (`<br>`). Otherwise consecutive non-blank lines in a paragraph
  are joined with a single space (the renderer collapses whitespace)."
  [lines]
  (loop [acc [] [ln & more] lines]
    (cond
      (nil? ln) acc
      (re-matches #".*  $" ln) (recur (-> acc
                                          (into (parse-inline (subs ln 0 (- (count ln) 2))))
                                          (conj [:br {}]))
                                      more)
      :else (recur (-> acc
                       (into (parse-inline ln))
                       (cond-> (seq more) (conj " ")))
                   more))))

(defn- consume-paragraph
  "Pull lines until the next block-boundary (blank / heading / list /
  fence / blockquote). Return `[para-hiccup remaining-lines]`."
  [lines]
  (let [block-boundary?
        (fn [ln]
          (or (blank? ln)
              (heading-level ln)
              (bullet-line? ln)
              (ordered-line? ln)
              (fence-line? ln)
              (blockquote-line? ln)))
        para (take-while (complement block-boundary?) lines)
        rest (drop  (count para) lines)]
    [(into [:p {}] (soft-break-pieces para)) rest]))

(defn- consume-bullet-list
  [lines]
  (let [items (take-while #(some? (bullet-line? %)) lines)
        rest  (drop (count items) lines)]
    [(into [:ul {}]
           (for [ln items]
             (let [[_ body] (bullet-line? ln)]
               (into [:li {}] (parse-inline body)))))
     rest]))

(defn- consume-ordered-list
  [lines]
  (let [items (take-while #(some? (ordered-line? %)) lines)
        rest  (drop (count items) lines)]
    [(into [:ol {}]
           (for [ln items]
             (let [[_ body] (ordered-line? ln)]
               (into [:li {}] (parse-inline body)))))
     rest]))

(defn- consume-blockquote
  [lines]
  (let [items (take-while #(some? (blockquote-line? %)) lines)
        rest  (drop (count items) lines)
        bodies (map blockquote-line? items)]
    [(into [:blockquote {}] (soft-break-pieces bodies))
     rest]))

(defn- consume-code-fence
  "Pulls one fenced code block. The opening fence is the first line; we
  collect raw lines verbatim until the matching closing fence (or EOF).
  `lang` becomes a `:data-lang` attribute so the CSS / future syntax-
  highlighting hook can target it."
  [[opener & more]]
  (let [lang  (fence-line? opener)
        body  (take-while #(not (fence-line? %)) more)
        rest  (drop (inc (count body)) more) ; +1 to skip the closing fence
        text  (str/join "\n" body)]
    [[:pre {:data-lang (or lang "")} [:code {} text]]
     rest]))

(defn parse
  "Parse a markdown string into a hiccup vector. Returns
  `[:div.rf-story-md ...]` containing the parsed block elements.

  Pure data → data; safe to call from JVM and CLJS. Empty / nil input
  produces an empty wrapper `[:div.rf-story-md]`."
  [s]
  (let [lines (if (string? s)
                (str/split-lines s)
                [])]
    (loop [acc   [:div.rf-story-md]
           lines lines]
      (if (empty? lines)
        acc
        (let [ln (first lines)]
          (cond
            (blank? ln)
            (recur acc (rest lines))

            (heading-level ln)
            (let [[lvl rest-text] (heading-level ln)
                  tag (keyword (str "h" (min 6 lvl)))]
              (recur (conj acc (into [tag {}] (parse-inline rest-text)))
                     (rest lines)))

            (fence-line? ln)
            (let [[block remaining] (consume-code-fence lines)]
              (recur (conj acc block) remaining))

            (bullet-line? ln)
            (let [[block remaining] (consume-bullet-list lines)]
              (recur (conj acc block) remaining))

            (ordered-line? ln)
            (let [[block remaining] (consume-ordered-list lines)]
              (recur (conj acc block) remaining))

            (blockquote-line? ln)
            (let [[block remaining] (consume-blockquote lines)]
              (recur (conj acc block) remaining))

            :else
            (let [[para remaining] (consume-paragraph lines)]
              (recur (conj acc para) remaining))))))))
