(ns realworld-resources.markdown
  "Hand-rolled markdown → hiccup for the RealWorld article body.

   The official RealWorld (Conduit) spec renders the article body as
   markdown; this is a small, ZERO-DEPENDENCY CLJS subset that covers the
   Conduit-realistic shapes and is SANITIZED BY CONSTRUCTION.

   Why hand-rolled (not markdown-it / a CLJS parser)
   -------------------------------------------------
   The examples tree carries minimal deps and pulling a JS/CLJS markdown
   library in would set a precedent (and a bundle cost) for a teaching
   example. Mike ruled (2026-06-11): a per-app, hand-rolled, sanitized
   subset. This file is intentionally near-identical to its sibling in
   `realworld/markdown.cljs` — duplication is acceptable per the ruling; a
   shared cross-app namespace would be a new shared surface.

   The subset
   ----------
   Block level : ATX headings (`# … ######`), fenced code blocks
                 (```lang … ```), blockquotes (`> …`), unordered lists
                 (`- ` / `* ` / `+ `), ordered lists (`1. `), and
                 paragraphs. Blank lines separate blocks.
   Inline      : `**bold**`/`__bold__`, `*italic*`/`_italic_`,
                 `` `code` ``, and `[text](href)` links.

   Out-of-domain input degrades GRACEFULLY: anything not recognised as one
   of the above is emitted as plain text (escaped by the renderer), so a
   malformed or exotic document never throws and never injects markup.

   Sanitization (XSS-safe by construction)
   ---------------------------------------
   1. We emit HICCUP, never a raw HTML string and never
      `dangerouslySetInnerHTML`. Every text fragment becomes a hiccup
      string child, which React/Reagent escapes — so `<script>…`, stray
      `<`/`>`, and `onerror=` attributes render as inert visible text and
      can never execute.
   2. Link hrefs are validated against a scheme allowlist
      (`http`/`https`/`mailto` + relative `/`, `#`, `./`, `../`). A
      `javascript:` / `data:` / `vbscript:` href is REJECTED — the link
      text is kept but the anchor is dropped (rendered as plain text), so
      no dangerous URL ever reaches the DOM.
   3. No attribute is ever taken from user input other than the validated
      `:href`; we never set ids/classes/styles/event handlers from the
      document."
  (:require [clojure.string :as str]))

;; ---------------------------------------------------------------------------
;; Link-href sanitization
;; ---------------------------------------------------------------------------

(def ^:private safe-url-schemes
  "Schemes a markdown link is allowed to navigate to."
  #{"http" "https" "mailto"})

(defn safe-href?
  "True when `href` is safe to place in an anchor's :href. Allows the
   allowlisted absolute schemes (http/https/mailto) and scheme-relative
   navigation (a relative path, an in-page #fragment, or `./`/`../`).
   Rejects everything else — notably `javascript:`, `data:`, and
   `vbscript:` URLs, the classic anchor-XSS vectors."
  [href]
  (let [h (str/trim (or href ""))]
    (cond
      (str/blank? h) false
      ;; A leading scheme `foo:` — allow only the allowlisted set. The regex
      ;; anchors at the start and refuses leading control/whitespace tricks
      ;; (e.g. "java\tscript:") because those are not a valid scheme char run.
      (re-find #"^[a-zA-Z][a-zA-Z0-9+.-]*:" h)
      (let [scheme (str/lower-case (re-find #"^[a-zA-Z][a-zA-Z0-9+.-]*" h))]
        (contains? safe-url-schemes scheme))
      ;; No scheme → relative path / #fragment / `./`/`../` / protocol-
      ;; relative. None can smuggle a script scheme, so it is safe navigation.
      :else true)))

;; ---------------------------------------------------------------------------
;; Inline parsing → hiccup fragment vector
;; ---------------------------------------------------------------------------
;;
;; Each inline parser scans `s` for its delimiter, splitting the run into a
;; flat vector of children (plain strings + hiccup elements). We thread the
;; text through the parsers in precedence order: code spans first (their
;; contents are literal, so no further inline parsing happens inside them),
;; then links, then bold, then italic. The result is a vector suitable to
;; splice into a hiccup parent with `(into [:p] (parse-inline …))`.

(declare parse-inline parse-code-spans parse-links parse-bold parse-italic)

(defn- tokenize
  "Generic single-delimiter inline tokenizer. Splits `s` on `re` (whose
   first capture group is the inner text), wrapping each match via
   `(wrap inner)` and recursing into the surrounding plain text with
   `recurse`. Non-matching text is returned as plain strings."
  [s re wrap recurse]
  (loop [rest s, acc []]
    (if-let [m (re-find re rest)]
      (let [whole (first m)
            inner (second m)
            idx   (str/index-of rest whole)
            before (subs rest 0 idx)
            after  (subs rest (+ idx (count whole)))]
        (recur after
               (-> acc
                   (into (recurse before))
                   (conj (wrap inner)))))
      (into acc (recurse rest)))))

(defn- parse-code-spans
  "Innermost: `` `code` `` becomes [:code \"code\"]. Code-span contents are
   literal — no further inline parsing happens inside, which is what makes
   them a safe terminal."
  [s]
  (tokenize s #"`([^`]+)`"
            (fn [inner] [:code inner])
            ;; surrounding text continues down the inline chain
            parse-links))

(defn- parse-links
  "`[text](href)` → an anchor with a sanitized :href, or — if the href is
   rejected — the bracketed text rendered as plain inline markup (so a
   `javascript:` link degrades to visible, inert text)."
  [s]
  (loop [rest s, acc []]
    (if-let [m (re-find #"\[([^\]]*)\]\(([^)\s]+)\)" rest)]
      (let [whole (first m)
            text  (nth m 1)
            href  (nth m 2)
            idx   (str/index-of rest whole)
            before (subs rest 0 idx)
            after  (subs rest (+ idx (count whole)))
            child  (if (safe-href? href)
                     (into [:a {:href href}] (parse-bold text))
                     ;; Unsafe href: keep the visible text, drop the anchor.
                     (into [:span] (parse-bold (str "[" text "](" href ")"))))]
        (recur after
               (-> acc
                   (into (parse-bold before))
                   (conj child))))
      (into acc (parse-bold rest)))))

(defn- parse-bold
  "`**bold**` / `__bold__` → [:strong …]."
  [s]
  (tokenize s #"(?:\*\*|__)(.+?)(?:\*\*|__)"
            (fn [inner] (into [:strong] (parse-italic inner)))
            parse-italic))

(defn- parse-italic
  "`*italic*` / `_italic_` → [:em …]. Terminal inline parser: its inner
   text is returned as a plain string (already past code/links/bold)."
  [s]
  (tokenize s #"(?:\*|_)([^*_]+)(?:\*|_)"
            (fn [inner] [:em inner])
            ;; deepest level: emit remaining text verbatim (escaped by render)
            (fn [t] (if (str/blank? t) [] [t]))))

(defn parse-inline
  "Parse a single line of inline markdown into a flat vector of hiccup
   children (strings + elements). Entry point: code spans → links → bold →
   italic → text."
  [s]
  (parse-code-spans s))

;; ---------------------------------------------------------------------------
;; Block parsing → seq of hiccup block elements
;; ---------------------------------------------------------------------------

(defn- heading-block [line]
  (when-let [m (re-find #"^(#{1,6})\s+(.*)$" line)]
    (let [level (count (nth m 1))
          text  (str/trim (nth m 2))]
      (into [(keyword (str "h" level))] (parse-inline text)))))

(defn- list-item? [line bullet-re]
  (re-find bullet-re line))

(def ^:private ul-re #"^\s*[-*+]\s+(.*)$")
(def ^:private ol-re #"^\s*\d+\.\s+(.*)$")

(defn- blockquote? [line] (re-find #"^\s*>\s?(.*)$" line))

(defn- blank? [line] (str/blank? line))

(defn- split-blocks
  "Group raw lines into blocks. Returns a seq of maps:
     {:type :code  :lang str :lines [..]}
     {:type :ul    :items [str ..]}
     {:type :ol    :items [str ..]}
     {:type :quote :lines [str ..]}
     {:type :head  :line str}
     {:type :para  :lines [str ..]}
   Fenced code blocks (``` …) are captured verbatim (no inline parsing of
   their contents); everything else is grouped by blank-line separation and
   line-prefix."
  [lines]
  (loop [ls lines, blocks []]
    (if (empty? ls)
      blocks
      (let [line (first ls)]
        (cond
          ;; Fenced code block: consume until the closing fence (or EOF).
          (re-find #"^\s*```" line)
          (let [lang (str/trim (str/replace line #"^\s*```" ""))
                [body remaining]
                (loop [rest (next ls), body []]
                  (cond
                    (empty? rest) [body rest]
                    (re-find #"^\s*```" (first rest)) [body (next rest)]
                    :else (recur (next rest) (conj body (first rest)))))]
            (recur remaining (conj blocks {:type :code :lang lang :lines body})))

          (blank? line)
          (recur (next ls) blocks)

          (heading-block line)
          (recur (next ls) (conj blocks {:type :head :line line}))

          (list-item? line ul-re)
          (let [[items remaining]
                (loop [rest ls, items []]
                  (if (and (seq rest) (list-item? (first rest) ul-re))
                    (recur (next rest) (conj items (second (re-find ul-re (first rest)))))
                    [items rest]))]
            (recur remaining (conj blocks {:type :ul :items items})))

          (list-item? line ol-re)
          (let [[items remaining]
                (loop [rest ls, items []]
                  (if (and (seq rest) (list-item? (first rest) ol-re))
                    (recur (next rest) (conj items (second (re-find ol-re (first rest)))))
                    [items rest]))]
            (recur remaining (conj blocks {:type :ol :items items})))

          (blockquote? line)
          (let [[qlines remaining]
                (loop [rest ls, qlines []]
                  (if (and (seq rest) (blockquote? (first rest)))
                    (recur (next rest) (conj qlines (second (blockquote? (first rest)))))
                    [qlines rest]))]
            (recur remaining (conj blocks {:type :quote :lines qlines})))

          ;; Paragraph: consecutive non-blank, non-special lines.
          :else
          (let [[plines remaining]
                (loop [rest ls, plines []]
                  (if (and (seq rest)
                           (not (blank? (first rest)))
                           (not (re-find #"^\s*```" (first rest)))
                           (not (heading-block (first rest)))
                           (not (list-item? (first rest) ul-re))
                           (not (list-item? (first rest) ol-re))
                           (not (blockquote? (first rest))))
                    (recur (next rest) (conj plines (first rest)))
                    [plines rest]))]
            (recur remaining (conj blocks {:type :para :lines plines}))))))))

(defn- render-block [block]
  (case (:type block)
    :code  [:pre [:code (str/join "\n" (:lines block))]]
    :head  (heading-block (:line block))
    :ul    (into [:ul] (for [item (:items block)] (into [:li] (parse-inline item))))
    :ol    (into [:ol] (for [item (:items block)] (into [:li] (parse-inline item))))
    :quote (into [:blockquote] (parse-inline (str/join " " (:lines block))))
    :para  (into [:p] (parse-inline (str/join " " (:lines block))))
    ;; Unknown block type — degrade to escaped text.
    [:p (pr-str block)]))

(defn render
  "Render a markdown `source` string into a hiccup fragment ([:<> …]) for
   the RealWorld article body. Safe by construction: emits hiccup (never raw
   HTML), escapes all text, and sanitizes link hrefs. A nil/blank source
   renders an empty fragment."
  [source]
  (let [src (or source "")
        ;; Normalise CRLF so the line-based block parser is platform-stable.
        lines (str/split (str/replace src #"\r\n?" "\n") #"\n" -1)
        blocks (split-blocks lines)]
    (into [:<>] (map render-block blocks))))
