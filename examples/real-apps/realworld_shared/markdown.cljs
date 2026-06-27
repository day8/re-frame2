(ns realworld-shared.markdown
  "Markdown → hiccup for the RealWorld (Conduit) article body, shared by
   both realworld example apps (`realworld.*` and `realworld-resources.*`).

   The official RealWorld (Conduit) spec renders the article body as
   CommonMark. This uses a real CommonMark library so the clone is faithful:
   tables, nested lists, images, footnotes — the shapes a hand-rolled
   subset could not cover — render correctly, and the parser is not a
   maintenance/bug surface of our own.

   Library choice
   --------------
   `io.github.nextjournal/markdown`, used in HICCUP-EMITTING mode
   (`md/->hiccup`). In ClojureScript it tokenizes with `markdown-it` (a
   pure-JS CommonMark tokenizer — no DOM, runs in the browser AND in the
   node test runner) and renders the parsed AST to plain hiccup DATA. The
   alternative pure-CLJS path (markdown-to-hiccup → markdown-clj + hickory)
   was rejected: hickory's CLJS `parse` requires a browser DOM
   (`js/DOMParser`), so it throws under the node test runner and is a
   portability hazard. The cost is the markdown-it JS bundle
   (~45-50 KB gzipped); the benefit is real CommonMark fidelity and a
   data-only (never `dangerouslySetInnerHTML`) render path.

   Safe by construction (XSS)
   --------------------------
   1. `md/->hiccup` emits HICCUP, never a raw HTML string and never
      `dangerouslySetInnerHTML`. Every text fragment is a hiccup string
      child, which React/Reagent escapes — so a stray `<`/`>` renders inert.
   2. nextjournal runs markdown-it with `html: true`, so raw inline/block
      HTML (`<script>…`, `<img onerror=…>`) parses to `:html-inline` /
      `:html-block` AST nodes. We install custom renderers for those node
      types that emit the raw HTML as INERT ESCAPED TEXT (a hiccup string
      child) rather than letting it reach the DOM as markup. (The stock
      renderer has no handler and falls through to an error placeholder;
      ours is both safer-by-intent and tidier.)
   3. markdown-it does NO link/image scheme validation, so `:href` (links)
      and `:src` (images) arrive verbatim — the real XSS surface. We walk
      the emitted hiccup and DROP any `:href`/`:src` whose scheme is not
      allowlisted (http/https/mailto + relative paths/fragments). A
      `javascript:` / `data:` / `vbscript:` URL never reaches the DOM; the
      element renders without the attribute (link text / alt text kept)."
  (:require [clojure.walk :as walk]
            [clojure.string :as str]
            [nextjournal.markdown :as md]
            [nextjournal.markdown.transform :as md.transform]))

;; ---------------------------------------------------------------------------
;; URL-scheme sanitization (the only attacker-controlled values that survive
;; into the default hiccup are :href on <a> and :src on <img>)
;; ---------------------------------------------------------------------------

(def ^:private safe-url-schemes
  "Schemes a markdown link/image is allowed to navigate to / load."
  #{"http" "https" "mailto"})

(defn safe-url?
  "True when `url` is safe to place in an :href / :src. Allows the
   allowlisted absolute schemes (http/https/mailto) and scheme-relative
   navigation (a relative path, an in-page #fragment, or `./`/`../`).
   Rejects everything else — notably `javascript:`, `data:`, and
   `vbscript:` URLs, the classic anchor/img XSS vectors."
  [url]
  (let [u (str/trim (or url ""))]
    (cond
      (str/blank? u) false
      ;; A leading scheme `foo:` — allow only the allowlisted set. The regex
      ;; anchors at the start and refuses leading control/whitespace tricks
      ;; (e.g. "java\tscript:") because those are not a valid scheme char run.
      (re-find #"^[a-zA-Z][a-zA-Z0-9+.-]*:" u)
      (let [scheme (str/lower-case (re-find #"^[a-zA-Z][a-zA-Z0-9+.-]*" u))]
        (contains? safe-url-schemes scheme))
      ;; No scheme → relative path / #fragment / `./`/`../` / protocol-
      ;; relative. None can smuggle a script scheme, so it is safe navigation.
      :else true)))

(defn- scrub-attrs
  "Given a hiccup attribute map, drop any :href / :src whose URL is not
   scheme-allowlisted. The element survives without the unsafe attribute."
  [attrs]
  (cond-> attrs
    (and (contains? attrs :href) (not (safe-url? (:href attrs)))) (dissoc :href)
    (and (contains? attrs :src)  (not (safe-url? (:src attrs))))  (dissoc :src)))

(defn- sanitize-hiccup
  "Walk an emitted hiccup tree and scrub unsafe :href / :src attributes
   from every element's attribute map. Pure data in, pure data out."
  [hiccup]
  (walk/postwalk
    (fn [node]
      (if (and (vector? node)
               (keyword? (first node))
               (map? (second node)))
        (assoc node 1 (scrub-attrs (second node)))
        node))
    hiccup))

;; ---------------------------------------------------------------------------
;; Raw-HTML neutralisation (defence-in-depth)
;; ---------------------------------------------------------------------------
;;
;; nextjournal runs markdown-it with html:true, so raw inline/block HTML
;; parses to :html-inline / :html-block nodes (content is a :text node
;; holding the raw HTML string). We render that string as an inert hiccup
;; STRING child — React escapes it, so `<script>…` / `<img onerror=…>`
;; shows as visible, inert text and can never execute.

(defn- raw-html->text
  "Render an :html-inline / :html-block node as inert escaped text."
  [_ctx node]
  (let [raw (->> (:content node)
                 (keep :text)
                 (apply str))]
    [:span raw]))

(def ^:private hiccup-renderers
  "The default nextjournal renderers, plus explicit inert handlers for raw
   HTML node types so attacker HTML degrades to escaped text (never markup)."
  (assoc md.transform/default-hiccup-renderers
         :html-inline raw-html->text
         :html-block  raw-html->text))

;; ---------------------------------------------------------------------------
;; Public entry point
;; ---------------------------------------------------------------------------

(defn render
  "Render a markdown `source` string into a hiccup fragment ([:<> …]) for
   the RealWorld article body. Safe by construction: emits hiccup (never raw
   HTML), so React escapes all text; raw inline/block HTML degrades to inert
   escaped text; and unsafe link/image URL schemes are dropped. A nil/blank
   source renders an empty fragment."
  [source]
  (let [src (or source "")]
    (if (str/blank? src)
      [:<>]
      (-> (md/->hiccup hiccup-renderers (md/parse src))
          sanitize-hiccup))))
