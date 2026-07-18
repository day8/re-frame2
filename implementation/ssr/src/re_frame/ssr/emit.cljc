(ns re-frame.ssr.emit
  "Pure hiccup → HTML emitter. Per Spec 011 §The render-tree → HTML emitter.

  HTML5: void elements self-close bare, doctype prefix on demand, full
  attr/text escaping, `:tag#id.cls` keyword parsing, callable-head
  (fn / Var) component resolution. `render-to-string` returns ONE
  shape: an HTML STRING — the structural hash and HTTP response triple
  live in sibling namespaces (`re-frame.ssr.hash` /
  `re-frame.ssr.response`).

  ## One head grammar (rf2-j81hs)

  A KEYWORD head is a DOM / custom element. Always, on every host. This
  emitter used to probe `(registrar/lookup :view head)` first and resolve
  a registered view, which made `[:dashboard/card 7]` mean \"registered
  view\" here and `<card>` on every client substrate (Reagent's
  `parse-tag` runs `(name tag)`; UIx and Helix are not hiccup at all).
  A `.cljc` app sharing views across both — the point of the SSR story —
  therefore could not write a keyword head that meant one thing, and
  because the SERVER rendered it correctly the mistake survived every
  server-side test.

  Views are referenced by a CALLABLE head: the Var `reg-view` defs, or
  `(rf/view :id)`. Spec 004 + Conventions own this grammar; Spec 011's
  keyword-resolution prose was a non-owning spec extending it and has
  been corrected. Finishes rf2-n82bbu — these emitters were the last
  surface out of conformance.

  HTML escape helpers (`escape-html`, `escape-attr`, `attr-string`) live
  in `re-frame.ssr.html-helpers`, shared with the head/meta emitter.
  `attr-string` is re-exported below so consumers who
  `:require [re-frame.ssr.emit :as emit]` keep seeing it at
  `emit/attr-string`; the emitter calls `html/escape-html` directly."
  ;; rf2-j81hs — `re-frame.registrar` and `re-frame.interop` are no longer
  ;; required here. Both existed solely for the deleted keyword-view
  ;; branch: `registrar/lookup` resolved the head, `interop/debug-enabled?`
  ;; gated its source-coord injection. The emitter is now a pure
  ;; hiccup → HTML function with no registry dependency at all, which is
  ;; the honest shape — resolving a name was never the emitter's job.
  (:require [clojure.string]
            [re-frame.error :as error]
            [re-frame.late-bind :as late-bind]
            [re-frame.ssr.hash :as hash]
            [re-frame.ssr.html-helpers :as html]
            #?(:cljs [re-frame.substrate.plain-atom :as plain-atom-cljs])))

;; ---- shared HTML helpers --------------------------------------------------
;;
;; Re-export `attr-string` so callers that `:require [re-frame.ssr.emit :as
;; emit]` still resolve `emit/attr-string`. The producing ns is
;; `re-frame.ssr.html-helpers` (shared with `re-frame.ssr.head.emit`); the
;; entity-escape rules live there once. `escape-html` / `escape-attr` are
;; consumed directly via the `html/` alias — they have no `emit/`-qualified
;; consumers.

(def attr-string html/attr-string)

;; Per HTML5 spec, these elements are void — they self-close and have no
;; closing tag.
;;
;; Lockstep with `reagent2.impl.template/void-tags` (in the
;; `day8/reagent-slim-and-fast` artefact). Bundle isolation forbids
;; `:require` across artefacts (reagent-slim must not pull in
;; `re-frame.ssr` — that's the whole point of the slim artefact), so
;; the set is duplicated by intent. If HTML5 ever extends the void
;; element list (extraordinarily unlikely), update both copies. Per
;; reagent-slim IMPL-SPEC §14.3.
(def void-elements
  #{:area :base :br :col :embed :hr :img :input :link :meta :param :source
    :track :wbr})

;; ---- raw-text body tags ---------------------------------------------------
;;
;; `<script>` and `<style>` are HTML "raw text" elements: their content is
;; NOT parsed as markup, so the body emitter's `escape-html` (which rewrites
;; `<`→`&lt;`, `>`→`&gt;`, `&`→`&amp;`, `"`/`'` → entities) does the WRONG
;; thing — it is XSS-safe but silently CORRUPTS legitimate inline content
;; (`[:style "a > b {…}"]` → `a &gt; b`, `[:script "if (a<b){…}"]` →
;; `if (a&lt;b)`, an inline JSON-LD blob → broken JSON).
;;
;; There is no single correct escape for raw author content in the body:
;;   - JSON-LD wants `<` → `<` (round-trips through `JSON.parse`),
;;   - raw JS/CSS must NOT be `<`-escaped (JS does not decode `<`
;;     outside string literals — `if (a < b)` is a syntax error).
;; So body-position `<script>`/`<style>` with raw STRING content is
;; unsupported by design: it is fail-loud rather than silently corrupting
;; the author's content (or, worse, guessing an escape that opens an XSS or
;; breaks the script). The two structured channels are:
;;   - JSON-LD / structured `<head>` content → `reg-head` (its emitter
;;     applies the JSON-LD `<` escape — see `re-frame.ssr.head.emit`),
;;   - trusted inline JS/CSS → the host shell's trusted `:body-end` /
;;     `:head-extra` opt (caller-trusted, not author-data).
;; A `<script>`/`<style>` with NO string children (element-only or empty)
;; is left alone — it is structurally inert and the guard only fires on a
;; raw string child.
(def ^:private raw-text-tags #{"script" "style"})

;; Tag-name injection gate. Gate the tag
;; component itself: HTML5 / SVG / MathML element names require an ASCII
;; letter start, then letters / digits / hyphens. Reject anything else.
;;
;; Decision: fail-fast (throw) rather than escape-and-emit. A tag-name
;; outside the grammar has no safe wire interpretation — escaping would
;; produce `<img&#x20;src=...>` which no browser parses as a tag, just a
;; visible glyph.
;;
;; `:<>` (React fragment) and `:>` (Reagent-native) are special heads
;; consumed by `emit-element` BEFORE this validator runs — they never
;; reach `parse-tag-name`. The grammar below applies only to actual DOM
;; tag emissions.
(def ^:private tag-name-re
  ;; HTML5 §element-name + SVG element-name + MathML element-name all
  ;; share the same conservative ASCII grammar: leading letter, then
  ;; letters / digits / hyphens. Custom elements (per HTML5 §custom-
  ;; element-name) require an ASCII-lower first letter + a `-`; the
  ;; conservative grammar admits both standard elements and well-formed
  ;; custom-element names.
  ;;
  ;; XML-namespaced SVG/MathML tags carry a single colon-
  ;; separated prefix (e.g. `:svg:rect`, `:xlink:href`-style elements).
  ;; Admit one optional `prefix:` segment where the prefix follows the
  ;; same element-name grammar. A single colon only — embedded `<`, `>`,
  ;; whitespace, `=` (the tag-injection vectors) remain rejected, and a
  ;; bare/leading/trailing/double colon (`:rect`, `svg:`, `a::b`) still
  ;; throws because each segment must be a well-formed element name.
  #"(?:[A-Za-z][A-Za-z0-9-]*:)?[A-Za-z][A-Za-z0-9-]*")

(defn- validate-tag-name!
  "Throw `:rf.error/invalid-tag-name` if `tag-name` does not match the
  HTML5 / SVG / MathML element-name grammar (`[A-Za-z][A-Za-z0-9-]*`,
  optionally prefixed by a single XML namespace segment `prefix:`)."
  [tag-name source-kw]
  (when-not (and (string? tag-name)
                 (re-matches tag-name-re tag-name))
    (error/throw-error!
      :rf.error/invalid-tag-name
      'rf.ssr/emit
      (str "tag-name " (pr-str tag-name)
           " (from hiccup head " (pr-str source-kw) ")"
           " does not match the HTML5/SVG/MathML"
           " element-name grammar"
           " ([A-Za-z][A-Za-z0-9-]*, optionally"
           " namespaced prefix:local) — DOM tag-name"
           " injection forbidden. Use a grammar-valid element name.")
      {:recovery :use-a-valid-element-name
       :extra    {:tag-name tag-name
                  :source   source-kw}}))
  tag-name)

;; Tag-name parsing for the :div#id.cls syntax (Reagent / Hiccup
;; convention). Memoised per-render by keyword identity: the result
;; depends solely on the keyword, and a single shell repeats `:div` /
;; `:span` / `:p` thousands of times. The memo (`*tag-name-cache*`) is
;; bound at the emit entry points and never outlives one render pass;
;; when unbound (cold callers — tests, custom consumers) the call falls
;; through to the uncached parse, so the public surface is unchanged.

(defn- parse-tag-name*
  "Pure parse — the body the memo wraps."
  [tag-kw]
  (let [s     (name tag-kw)
        ;; Match: tag-name optionally followed by #id and .class fragments.
        [_ tag id classes] (re-matches #"([^#.]+)(?:#([^.]+))?(.*)" s)
        class-list (when (and classes (seq classes))
                     (->> (clojure.string/split classes #"\.")
                          (remove empty?)
                          (clojure.string/join " ")))]
    (validate-tag-name! tag tag-kw)
    [tag
     (cond-> {}
       id         (assoc :id id)
       class-list (assoc :class class-list))]))

(def ^:dynamic *tag-name-cache*
  "Per-render volatile! holding `{tag-kw [tag-name tag-attrs]}`. Bound
  at the public emit entry points; nil outside a render pass (cold
  callers fall through to the uncached parse). Per rf2-ezdwh."
  nil)

(defn parse-tag-name
  "Split a keyword like :div#main.col-12.bold into [:div {:id \"main\"
  :class \"col-12 bold\"}] components. Throws `:rf.error/invalid-tag-name`
  (rf2-z7gor) if the tag component is not a well-formed HTML5/SVG/MathML
  element name.

  When called inside a render pass (`*tag-name-cache*` bound), the
  result is memoised by keyword identity and reused on subsequent
  emissions of the same head — typical SSR shells repeat `:div`,
  `:span`, `:p` thousands of times. Per rf2-ezdwh."
  [tag-kw]
  (if-let [cache *tag-name-cache*]
    (or (get @cache tag-kw)
        (let [parsed (parse-tag-name* tag-kw)]
          (vswap! cache assoc tag-kw parsed)
          parsed))
    (parse-tag-name* tag-kw)))

(defn merge-class-attrs
  "Merge the class from the tag-name into the attrs map's :class.
  `merged` is the space-joined concatenation of whichever class strings
  exist, or nil when neither is present — nil is the documented
  \"omit :class\" signal the `cond->` below consumes."
  [tag-attrs user-attrs]
  (let [merged (some->> [(:class tag-attrs) (:class user-attrs)]
                        (remove nil?)
                        seq
                        (clojure.string/join " "))]
    (cond-> (merge tag-attrs (dissoc user-attrs :class))
      merged (assoc :class merged))))

(declare emit-element)

(defn- emit-children [children]
  (clojure.string/join (mapv emit-element children)))

(defn- emit-children-threading-root-attrs
  "Emit `children`, threading `root-attrs` (per rf2-lxwse) onto the FIRST
  child only (nil to the rest), so the render-hash / source-coord lands
  exactly once on the first DOM-tag element reachable through a fragment
  root. A fragment whose first child is itself a fragment / fn-head /
  view-ref recurses — `emit-element` keeps threading the same root-attrs
  down its own root path until a DOM tag consumes it. When `root-attrs`
  is nil this is identical to `emit-children`. Per rf2-58zvy1 finding 2 —
  the `:<>` branch previously dropped root-attrs via plain `emit-children`,
  so a fragment-rooted SSR tree lost the `data-rf-render-hash` marker the
  emitter docstring promises."
  [children root-attrs]
  (if (nil? root-attrs)
    (emit-children children)
    (let [v (vec children)]
      (clojure.string/join
        (map-indexed (fn [i child]
                       (emit-element child (when (zero? i) root-attrs)))
                     v)))))

;; ---- source-coord annotation on registered-view roots --------------------
;;
;; ⚠ ORPHANED BY rf2-j81hs — HANDED TO rf2-8vi4q. Read before reusing.
;;
;; Per Spec 006 §Source-coord annotation (rf2-z7f7 / rf2-z9n1) and
;; Spec 011 §Source-coord annotation under SSR, the SSR emitter injected
;; `data-rf2-source-coord="<ns>:<sym>:<line>:<col>"` on a registered
;; view's root DOM element so pair-tool consumers could map server-
;; rendered HTML back to the reg-view call site.
;;
;; The ONLY call site was the keyword-view branch of `emit-element` (and
;; its mirror in the streaming walker). rf2-j81hs deleted that branch, so
;; BOTH fns below are now unreachable from this namespace: no server
;; render annotates anything. That is the ruled outcome, not an oversight
;; — per the rf2-j81hs ruling §5 the keyword-branch coord injection "dies
;; WITH the branch", and per rf2-8vi4q's own evidence it never fired on
;; any boundary a hydratable page can contain (isomorphic pages compose
;; via `(rf/view :id)` fn-refs, where the JVM stores the raw unwrapped
;; handler-fn, so emitter-side annotation never ran there anyway).
;;
;; The fns are LEFT IN PLACE deliberately. rf2-8vi4q is the bead that
;; owns this surface: its ruling moves annotation to the reg-view
;; REGISTRATION boundary (a debug-gated wrapper on the registered
;; `:handler-fn`, mirroring Spec 006's client injection) and deletes
;; `inject-coord-on-root-hiccup` plus its call sites as step 2. Deleting
;; them here would pre-empt that design and strand its tests; the call
;; sites are already gone, which is this bead's half of the work.
;;
;; Until rf2-8vi4q lands there is NO server-side source-coord annotation.
;; `re-frame.ssr-source-coord-test` pins exactly that interim contract.

(defn format-view-source-coord
  "Render the registered view's metadata as the attribute value
  `<ns>:<sym>:<line>:<col>` per Spec 006 §Source-coord annotation. Returns
  nil when the slot has no captured coords (programmatic registration that
  bypassed the macro path) — the emitter then skips the annotation."
  [id slot]
  (when (or (:ns slot) (:line slot) (:file slot) (:column slot))
    (let [ns-part  (or (namespace id) "?")
          sym-part (name id)
          line     (:line slot)
          col      (:column slot)]
      (str ns-part ":" sym-part ":"
           (if line (str line) "?")
           ":"
           (if col (str col) "?")))))

(defn inject-coord-on-root-hiccup
  "Inject :data-rf2-source-coord into the root element of a hiccup form,
  if the root is a DOM-tag keyword. Mirrors the CLJS-side wrapper in
  re-frame.views per Spec 006 §Source-coord annotation. Non-DOM roots
  (fragment :<>, fn-or-component head, lazy-seq) are returned unchanged
  — pair tools fall back to :rf/id for those (documented exemption)."
  [coord out]
  (cond
    (and (vector? out)
         (keyword? (first out))
         (not= :<> (first out))
         (not= :> (first out)))
    (let [head        (first out)
          maybe-attrs (second out)]
      (if (map? maybe-attrs)
        (if (contains? maybe-attrs :data-rf2-source-coord)
          out
          (into [head (assoc maybe-attrs :data-rf2-source-coord coord)]
                (drop 2 out)))
        (into [head {:data-rf2-source-coord coord}] (rest out))))

    :else out))

;; ---- root-attrs injection (per rf2-lxwse) --------------------------------
;;
;; The render-hash (data-rf-render-hash) is stamped on the first DOM-tag
;; element of the rendered tree. Historically this used a post-emit regex
;; replace on the output string; rf2-lxwse refactored that into a
;; structural injection on the hiccup root before stringification — the
;; same pattern as `inject-coord-on-root-hiccup` above. To compose with
;; that source-coord injection (which only runs inside the view-ref
;; resolution branch of `emit-element`), the injection threads an optional
;; `root-attrs` map down through `emit-element` and consumes it on the
;; first DOM-tag emission — past any view-refs, fragments (`:<>`),
;; Reagent-native heads (`:>`), or fn-headed components on the root path.
;; Non-DOM-rooted trees silently no-op on the injection (matches the
;; source-coord exemption).

(defn merge-root-attrs
  "Merge root-level injected attrs (per rf2-lxwse) into the attrs map of
  a DOM tag. Existing attribute values win — the injected attr is only
  added when the key isn't already present, so a caller-supplied
  `data-rf-render-hash` on the root never gets overwritten."
  [attrs root-attrs]
  (reduce-kv (fn [m k v]
               (if (contains? m k) m (assoc m k v)))
             attrs
             root-attrs))

(defn reject-raw-text-string-children!
  "Throw `:rf.error/ssr-raw-text-in-body` when a body-position raw-text
  element (`<script>` / `<style>`, per `raw-text-tags`) carries a raw
  STRING child. Per rf2-ee38b.10 — the body emitter cannot apply a single
  correct escape to raw author content (JSON-LD needs `<`→`\\u003c`; raw
  JS/CSS must not be `<`-escaped at all), so silently `escape-html`-ing it
  corrupted legit inline content while masking the lack of a real channel.
  Fail loud and point the author at the structured surfaces. Element-only
  / empty `<script>`/`<style>` is left alone (no string child → no throw).

  rf2-hzttr finding 3 — the raw-text classification is CASE-INSENSITIVE:
  `<SCRIPT>` / `<Style>` (admitted by `validate-tag-name!`) must hit the
  same guard as their lower-case spellings, or an upper-case tag silently
  bypasses the body-position script/style protection (XSS-adjacent). The
  membership test lower-cases the tag; the error message keeps the
  author's original casing."
  [tag-name children source-head]
  (when (and (contains? raw-text-tags (clojure.string/lower-case tag-name))
             (some string? children))
    (error/throw-error!
      :rf.error/ssr-raw-text-in-body
      'rf.ssr/emit
      (str "Raw string content under a body-position <"
           tag-name "> (hiccup head " (pr-str source-head)
           ") is unsupported — the body emitter has no"
           " single safe escape for raw script/style"
           " content. Put JSON-LD / structured head"
           " content through reg-head, and trusted"
           " inline JS/CSS through the host shell's"
           " trusted :body-end / :head-extra opt.")
      {:recovery :move-content-to-reg-head-or-shell-opts
       :extra    {:tag    tag-name
                  :source source-head}})))

(defn reserved-rf-head?
  "True when `head` is a keyword in the framework-reserved `:rf/*` scheme
  — the bare `rf` namespace (`:rf/suspense-boundary`) or a dotted
  subsystem segment under it (`:rf.ssr/…`). Per Conventions §Reserved
  namespaces the whole scheme is framework-owned, so no author DOM element
  can legitimately live there.

  rf2-j81hs — the discriminator for the reserved-head guard below. Callers
  consume the RECOGNISED reserved heads (`:>`, `:rf/suspense-boundary`)
  before consulting this, so a `true` here means \"reserved namespace, not
  a marker this emitter implements\"."
  [head]
  (when-let [ns* (and (keyword? head) (namespace head))]
    (or (= "rf" ns*)
        (clojure.string/starts-with? ns* "rf."))))

(defn reject-reserved-rf-hiccup-head!
  "Throw `:rf.error/invalid-hiccup-head` for an UNRECOGNISED head in the
  framework-reserved `:rf/*` scheme.

  rf2-j81hs — with keyword heads now uniformly DOM/custom elements
  (§Keyword heads below), a reserved-namespace head would otherwise sail
  through the element branch: `:rf/suspense-boundry` (typo) has a `name`
  that passes the `[A-Za-z][A-Za-z0-9-]*` tag grammar, so the emitter
  would paint a phantom `<suspense-boundry>` and say nothing — the exact
  silent-phantom failure mode this bead exists to kill, just moved one
  keystroke away. The `:rf/*` root is framework-owned, so there is no
  legitimate author element to preserve here and the guard costs one
  namespace test on a branch that already destructures the keyword.

  Reuses `:rf.error/invalid-hiccup-head` rather than minting a near-
  duplicate id: the head genuinely has no HTML interpretation, which is
  precisely what that id names. The message and `:recovery` distinguish
  the arm."
  [el head]
  (error/throw-error!
    :rf.error/invalid-hiccup-head
    'rf.ssr/emit
    (str "hiccup vector head " (pr-str head)
         " (in element " (pr-str el) ") is in the framework-reserved"
         " :rf/* namespace but is not a hiccup head this emitter"
         " recognises. The recognised reserved heads are :<> (fragment),"
         " :> (Reagent-native interop) and :rf/suspense-boundary"
         " (streaming, shell walker only). The :rf/* scheme is framework-"
         "owned (Conventions §Reserved namespaces), so this cannot be an"
         " author DOM element — emitting it would paint a phantom <"
         (name head) "> element silently. Check the spelling, or use an"
         " unreserved keyword if you meant a custom element.")
    {:recovery :use-a-recognised-reserved-head-or-an-unreserved-keyword
     :extra    {:head    head
                :element el}}))

(defn reject-invalid-hiccup-head!
  "Throw `:rf.error/invalid-hiccup-head` for a hiccup vector whose head is
  neither a keyword (DOM tag / `:<>` / `:>` / `:rf/suspense-boundary`) nor
  a callable component (a fn or Var). A head that is a string / nil /
  number / boolean / collection has no HTML interpretation.

  Per rf2-y1jbaq — the prior `(str el)` fallthrough stringified the WHOLE
  hiccup vector RAW and UNESCAPED onto the wire, so a malformed-head vector
  carrying attacker-controlled child strings (`[nil \"<script>…\"]`,
  `[\"x\" \"<img … onerror=…>\"]`) shipped live `<script>` / `<img onerror>`
  markup — an XSS-class bypass of the locked escape-at-every-leaf-or-fail-
  loud invariant (Spec 011 §XSS at output boundaries). Fail loud instead,
  mirroring `validate-tag-name!` and the `:>` / `:rf/suspense-boundary`
  throws — never stringify an unescaped hiccup form to the wire. Shared by
  the sync emitter and the streaming shell walker so both paths reject the
  same malformed shape identically."
  [el]
  (error/throw-error!
    :rf.error/invalid-hiccup-head
    'rf.ssr/emit
    (str "hiccup vector head " (pr-str (first el))
         " (in element " (pr-str el) ") is not a valid hiccup head — a head"
         " must be a keyword (DOM tag / :<> / :> /"
         " :rf/suspense-boundary) or a callable component (fn / Var). A"
         " string / nil / number / boolean / collection head has no HTML"
         " interpretation; emitting its EDN form raw would bypass output"
         " escaping (XSS). Produce a valid hiccup head.")
    {:recovery :use-a-keyword-or-callable-hiccup-head
     :extra    {:head    (first el)
                :element el}}))

(defn- invoke-form-2-render-fn
  "Invoke a Form-2 inner render fn with `args`, tolerating an inner that
  declares FEWER params than the component was passed. The idiomatic
  Reagent/UIx/Helix Form-2 inner either takes the SAME args as the outer
  (`(fn [x] …)`) or ignores them and closes over the outer's (`(fn [] …)`).
  On CLJS these are equivalent — JS arity leniency drops extra args — so
  `(apply inner args)` renders both. The JVM is strict: `(apply inner args)`
  throws `ArityException` for a lower-arity inner, so emulate CLJS leniency
  by falling back to a no-arg call (the `(fn [] …)` shape)."
  [inner args]
  #?(:clj  (try
             (apply inner args)
             (catch clojure.lang.ArityException _
               (inner)))
     :cljs (apply inner args)))

(defn resolve-component-head
  "Resolve a callable-head hiccup component (`[component & args]`, where
  `component` is a fn or a Var — the idiomatic Reagent/UIx/Helix SSR shape,
  the same shape the render-tree hash walker supports) to renderable hiccup.

  A Form-1 component returns hiccup directly. A Form-2 component returns an
  INNER render fn; per Reagent/UIx/Helix Form-2 semantics it is invoked once
  more with the SAME args (arity-tolerantly — see
  `invoke-form-2-render-fn`) to obtain the hiccup. Resolving Form-2 here
  does NOT perturb the structural hash — the hash walks the RAW tree
  (`[component …]`, a raw fn head serialising to one fixed token, hash.cljc),
  identical on server and client, so the resolved output cannot fire a
  spurious hydration mismatch.

  Per rf2-dtza9a — the prior bare `(apply head args)` left a Form-2 result
  (a fn) to fall through to `escape-html`, which stringified the fn's
  `.toString` (`user$…fn__…@…`) as visible page text (plus a guaranteed
  downstream hydration mismatch). A result that is STILL a bare fn after the
  single Form-2 unwrap is not a valid component render — fail loud with
  `:rf.error/ssr-nonrenderable-component` rather than leak the fn text."
  [head args]
  (let [resolved (apply head args)]
    (if (fn? resolved)
      ;; Form-2 — the outer fn returned an inner render fn; invoke it once
      ;; with the same args (Form-2 semantics) to get the hiccup.
      (let [rendered (invoke-form-2-render-fn resolved args)]
        (if (fn? rendered)
          (error/throw-error!
            :rf.error/ssr-nonrenderable-component
            'rf.ssr/emit
            (str "a callable hiccup component resolved to a fn even after the"
                 " Form-2 unwrap (outer fn → inner render fn → still a fn). A"
                 " Form-2 component's inner render fn must return hiccup, not"
                 " another fn; SSR cannot render a bare fn — it would"
                 " stringify the fn's .toString as visible page text. Return"
                 " hiccup from the component's render fn.")
            {:recovery :return-hiccup-from-the-component-render-fn
             :extra    {:component head}})
          rendered))
      resolved)))

(defn emit-element
  "Emit a hiccup node as an HTML string. The optional `root-attrs` map
  (per rf2-lxwse) carries attributes destined for the first DOM-tag
  element on the root path — view-refs, fragments, Reagent-native heads,
  and fn-headed components pass it through; the first DOM-tag emission
  merges and consumes it. Recursive calls into children always pass
  `nil` so the injection lands on the root only."
  ([el] (emit-element el nil))
  ([el root-attrs]
   (cond
     (nil? el)         ""
     (string? el)      (html/escape-html el)
     ;; Canonicalise the numeric print form so the emitted HTML matches the
     ;; render-tree hash byte-for-byte across runtimes (rf2-0ypnnk): a
     ;; whole-valued double renders `9` (not the JVM `9.0`), agreeing with
     ;; CLJS. `canonical-number` uses `pr-str`, which coincides with `str`
     ;; for numbers (no quoting), so ordinary integers/decimals are
     ;; unchanged.
     (number? el)      (hash/canonical-number el)
     (boolean? el)     ""
     (vector? el)
     (let [head (first el)]
       (cond
         ;; Fragment `:<>` — emits its rendered children with no wrapper.
         ;; Per Spec 011: source-coord annotation skips this head (the
         ;; fragment itself is not a DOM element), and the tag-name
         ;; validator (rf2-z7gor) does not apply. Handled ahead of the
         ;; general keyword branch so it never reaches `parse-tag-name`.
         ;;
         ;; rf2-58zvy1 finding 2 — root-attrs (rf2-lxwse, the render-hash
         ;; / `:render-hash` marker) MUST thread through a fragment root
         ;; onto the first DOM-tag child, exactly once. The prior plain
         ;; `emit-children` dropped them, so `[:<> [:div "x"]]` with
         ;; `{:emit-hash? true}` lost its `data-rf-render-hash` even though
         ;; the docstring promises threading "past … fragments". Thread
         ;; onto the first child only; nested fragments / fn-heads /
         ;; view-refs keep threading down their own root path.
         (= :<> head)
         (emit-children-threading-root-attrs (rest el) root-attrs)

         ;; Reagent-native interop head `:>` — `[:> Component {props} …]`
         ;; passes its children through to a React COMPONENT, not a DOM
         ;; tag. There is no React on the JVM, so `:>` cannot be statically
         ;; rendered server-side. Per rf2-ee38b.10 — fail loud rather than
         ;; splice `(rest el)` through `emit-children`, which would stringify
         ;; the component ref and dump the props map as raw EDN into the
         ;; markup (garbage output, not a rendered component). The author
         ;; must wrap the React component in a `reg-view` (which the SSR
         ;; emitter resolves) or render it client-only.
         (= :> head)
         (error/throw-error!
           :rf.error/ssr-reagent-native-head
           'rf.ssr/emit
           (str "Reagent-native interop head `:>` "
                "(element " (pr-str el) ") cannot be "
                "rendered server-side — it targets a "
                "React component and there is no React "
                "on the JVM. Wrap the component in a "
                "reg-view for SSR, or render it "
                "client-only.")
           {:recovery :wrap-in-reg-view-or-render-client-only
            :extra    {:element el}})

         ;; Reserved streaming marker `:rf/suspense-boundary` — recognised
         ;; ONLY by the streaming shell walker (`re-frame.ssr.streaming`).
         ;; The standard emitter must NOT treat it as a DOM tag: its
         ;; name passes the `[A-Za-z][A-Za-z0-9-]*` tag grammar, so
         ;; without this guard `parse-tag-name` would emit a phantom
         ;; `<suspense-boundary>` element with the `{:id … :fallback …}`
         ;; attrs map serialised as bogus attributes (rf2-bee5i). Fail
         ;; loud — parallel to the `:>` throw above — so a marker that
         ;; reaches a non-streaming render (e.g. `render-to-string` on a
         ;; streaming tree) surfaces a structured error rather than
         ;; silently producing malformed markup. Per Conventions §`:rf/*`
         ;; reserved hiccup heads + Spec 011 §Streaming SSR.
         (= :rf/suspense-boundary head)
         (error/throw-error!
           :rf.error/ssr-suspense-boundary-outside-stream
           'rf.ssr/emit
           (str ":rf/suspense-boundary (element "
                (pr-str el) ") is a streaming-only "
                "marker recognised by the streaming "
                "shell walker (re-frame.ssr.ring/"
                "stream-handler), not the standard "
                "emitter. It reached render-to-string "
                "outside a stream — that path cannot "
                "resolve the boundary's continuation, "
                "so it would emit a phantom "
                "<suspense-boundary> DOM element. Use "
                "stream-handler to render trees "
                "containing :rf/suspense-boundary.")
           {:recovery :render-via-stream-handler
            :extra    {:element el}})

         ;; An unrecognised head in the framework-reserved `:rf/*` scheme.
         ;; The recognised reserved heads are consumed above (`:<>`, `:>`,
         ;; `:rf/suspense-boundary`); anything else under the reserved root
         ;; is a typo or a marker this emitter does not implement, and its
         ;; name passes the `[A-Za-z][A-Za-z0-9-]*` tag grammar — so
         ;; falling through to the element branch would paint a phantom
         ;; `<suspense-boundry>` / `<hydrate>` and say nothing. Per
         ;; Conventions §Reserved namespaces the `:rf/*` root is framework-
         ;; owned, so no author element can legitimately live there; fail
         ;; loud (rf2-j81hs §4). Reuses `:rf.error/invalid-hiccup-head` —
         ;; the head genuinely has no HTML interpretation, which is exactly
         ;; what that id names — rather than minting a near-duplicate id.
         (reserved-rf-head? head)
         (reject-reserved-rf-hiccup-head! el head)

         (keyword? head)
         ;; rf2-j81hs — ONE render-tree head grammar, corpus-wide: a
         ;; keyword head is a DOM / custom element on EVERY host. This
         ;; branch used to probe `(registrar/lookup :view head)` first and
         ;; resolve a registered view, which made `[:dashboard/card 7]`
         ;; mean "registered view" here and "an HTML `<card>` element" on
         ;; every client substrate (Reagent's `parse-tag` runs `(name
         ;; tag)`; UIx/Helix are not hiccup at all). A `.cljc` app sharing
         ;; views across both — the whole point of the SSR story — could
         ;; not write a keyword head that meant one thing, and the server
         ;; rendered it CORRECTLY while the client painted a phantom, so
         ;; the mistake survived every server-side test (rf2-o4rbh found it
         ;; in the flagship streaming example).
         ;;
         ;; Spec 004 + Conventions own the head grammar; Spec 011's
         ;; keyword-resolution prose was a non-owning spec extending it and
         ;; is CORRECTED, not changed (rf2-3i7tr grammar ownership). This
         ;; finishes rf2-n82bbu — the JVM emitters were the last surface
         ;; out of conformance with "keyword tags stay plain substrate-
         ;; owned HTML elements". Views are referenced by callable binding:
         ;; the Var `reg-view` defs, or `(rf/view :id)`.
         (let [[tag-name tag-attrs] (parse-tag-name head)
               [user-attrs children]
               (if (map? (second el))
                 [(second el) (drop 2 el)]
                 [{} (rest el)])
               merged-attrs (merge-class-attrs tag-attrs user-attrs)
               attrs        (if root-attrs
                              (merge-root-attrs merged-attrs root-attrs)
                              merged-attrs)
               ;; rf2-hzttr finding 3 — void + raw-text classification
               ;; must be CASE-INSENSITIVE. `validate-tag-name!` admits
               ;; upper/mixed-case names (`[:BR]`, `[:SCRIPT …]`), but
               ;; `void-elements` / `raw-text-tags` are keyed lower-case,
               ;; so a `[:BR]` was emitted as a non-void open+close pair
               ;; and a `[:SCRIPT "if (a<b)"]` bypassed the raw-text body
               ;; guard (XSS-adjacent). Normalise for classification while
               ;; preserving the author's emitted case.
               norm-tag     (clojure.string/lower-case tag-name)
               void?        (contains? void-elements (keyword norm-tag))]
           (if void?
             (str "<" tag-name (attr-string attrs) ">")
             (do
               (reject-raw-text-string-children! tag-name children head)
               (str "<" tag-name (attr-string attrs) ">"
                    (emit-children children)
                    "</" tag-name ">"))))

         ;; Callable component head — a plain fn OR a Var reference
         ;; (`[#'component & args]`). On the JVM a Var is `ifn?` but NOT
         ;; `fn?`, so a bare `(fn? head)` test let a Var-headed component
         ;; fall through to `:else (str el)` and emit the EDN text
         ;; `[#'user/component "ok"]` instead of resolving it (rf2-wtd8z
         ;; finding 2). `ifn?` covers both — keywords/`:<>`/`:>`/
         ;; `:rf/suspense-boundary` are all consumed by the branches above,
         ;; so the only callables reaching here are fns and Var references.
         ;; Pass root-attrs through this indirection too — structurally the
         ;; same kind of wrapping as a registered-view ref, so the root
         ;; hash / source-coord thread through the Var head onto the
         ;; resolved DOM root.
         ;;
         ;; rf2-dtza9a — `resolve-component-head` handles a Form-2 component
         ;; (an outer fn returning an inner render fn): the inner fn is
         ;; invoked once with the same args rather than left to fall through
         ;; to `escape-html`, which stringified the fn's `.toString` as
         ;; visible page text.
         (ifn? head)
         (emit-element (resolve-component-head head (rest el)) root-attrs)

         ;; rf2-y1jbaq — a vector whose head is not a keyword and not a
         ;; callable (string / nil / number / boolean / collection head) is
         ;; malformed. The prior `(str el)` shipped its EDN form RAW and
         ;; UNESCAPED (XSS-class escape bypass); fail loud instead.
         :else (reject-invalid-hiccup-head! el)))

     ;; A non-vector sequential ROOT (a lazy-seq / list — e.g. the result of
     ;; `(map …)` or `(for …)` at the root). Per Spec 011 §Source-coord
     ;; annotation / §Hydration-mismatch detection a lazy-seq root is
     ;; "passed through the injection — the attribute lands on the eventual
     ;; DOM root." rf2-a73idu — the prior plain `emit-children` DROPPED
     ;; `root-attrs`, so a lazy-seq-rooted tree silently lost its
     ;; `data-rf-render-hash` marker; thread it onto the first DOM child,
     ;; exactly like the `:<>` fragment root.
     (sequential? el) (emit-children-threading-root-attrs el root-attrs)
     :else (html/escape-html el))))

(defn render-to-string
  "Pure hiccup → HTML string. Per Spec 011 §The render-tree → HTML
  emitter. Returns a STRING. The structural hash (`render-tree-hash`)
  and the HTTP response accumulator (`re-frame.ssr/get-response`, backed
  by the framework-private `response-slots` side-channel atom per
  rf2-jbcmt — Spec 011 §Response storage substrate) are separate surfaces.

  Implements HTML5 void elements, :tag#id.cls parsing, boolean attrs,
  text/attr escaping, registered-view resolution, var-reference
  resolution, :doctype? prefix, and :emit-hash? root-element hash
  injection for client-side mismatch detection.

  Per rf2-lxwse: when `:emit-hash?` is true, `data-rf-render-hash` is
  threaded as `root-attrs` through `emit-element` and merged onto the
  first DOM-tag element of the rendered tree — past view-refs, fragments,
  Reagent-native heads, and fn-headed components on the root path. This
  replaces the prior post-emit regex-on-string injection: structural,
  composes with the source-coord annotation, and silently no-ops for
  non-DOM-rooted trees (matching the source-coord exemption).

  Per rf2-atmvj / rf2-i15nh: callers that also need the structural hash
  for the payload (e.g. the ssr-ring pipeline's `:rf/render-hash`) MUST
  pass it in via `:render-hash` — that single hash then drives BOTH the
  root-element `data-rf-render-hash` injection AND the caller's payload
  slot. Without the opt, `:emit-hash? true` falls back to computing the
  hash internally (one extra canonical-EDN walk over the tree); a caller
  that ALSO calls `ssr/render-tree-hash` separately pays a second walk.
  The opt eliminates the duplicate without changing the byte-identity
  contract — `:render-hash` is just a pass-through to the root-attrs
  stamper. Spec 011's hash/emit separation is preserved (no combined
  walker)."
  [render-tree opts]
  ;; Per rf2-ezdwh — bind the per-render parse-tag-name memo so
  ;; repeated heads (`:div`, `:span`, `:p`, …) parse once instead of
  ;; once per emission. Cache lives only for the duration of this
  ;; render call.
  (binding [*tag-name-cache* (volatile! {})]
    (let [supplied-hash (:render-hash opts)
          root-attrs    (cond
                          supplied-hash {:data-rf-render-hash supplied-hash}
                          (:emit-hash? opts)
                          {:data-rf-render-hash (hash/render-tree-hash render-tree)})
          body          (emit-element render-tree root-attrs)]
      (if (:doctype? opts)
        (str "<!DOCTYPE html>" body)
        body))))

;; Wire render-to-string into the plain-atom adapter so callers using
;; rf/render-to-string (delegating through the substrate adapter) get
;; this implementation. Per rf2-uo7v the Reagent adapter wires its own
;; set-hiccup-emitter! through `:reagent/set-hiccup-emitter!`; we
;; consume that hook below so ssr does not statically :require the
;; Reagent adapter ns.
#?(:clj
   (try
     (require 're-frame.substrate.plain-atom)
     ((requiring-resolve 're-frame.substrate.plain-atom/set-hiccup-emitter!)
      render-to-string)
     (catch Throwable _ nil)))

#?(:cljs
   (plain-atom-cljs/set-hiccup-emitter! render-to-string))

;; Reagent adapter wiring (load-order-symmetric counterpart to the
;; plain-atom path above). No-op when the Reagent adapter isn't on the
;; classpath.
(when-let [reagent-set-emitter! (late-bind/get-fn :reagent/set-hiccup-emitter!)]
  (reagent-set-emitter! render-to-string))

;; rf2-vxgfnd.204 — retain the current SSR emitter durably so a substrate
;; adapter can RE-ARM its render-to-string slot at EVERY install, not only the
;; one-time ns-load publications above. The React-shaped adapters (re-frame.ui,
;; UIx, Helix) clear their per-generation `emitter-cell` on `dispose-adapter!`
;; (spine `dispose-active-roots-and-caches!`), so a public destroy → re-init
;; cycle — or an SSR-loaded-before-adapter load order, where the chain lookup
;; above finds no adapter yet — would otherwise leave `render-to-string`
;; unarmed (`:rf.error/no-hiccup-emitter-bound`). This slot is the single
;; authoritative source `re-frame.substrate.adapter/install-adapter!` replays
;; from at each install; disposal still clears each adapter's own slot, and the
;; emitter is never retained inside a disposed adapter. Host-neutral: the
;; JVM/plain-atom adapter retains its emitter across its no-op dispose, so its
;; install replay is a harmless idempotent re-apply. Load-order symmetric with
;; the publications above — whichever of ssr / adapter loads last, the durable
;; slot plus the install replay converge on the same armed state.
(late-bind/set-fn! :ssr/current-hiccup-emitter render-to-string)

(defn install-render-to-string!
  "Install this ns's render-to-string into a substrate adapter's
  :render-to-string slot. Called by adapter namespaces that ship in
  their own artefact for hosts that wire a custom adapter directly.
  Per Spec 006 §Adapter shipping convention (rf2-0hxm).

  The bundled Reagent adapter wires itself via the
  `:reagent/set-hiccup-emitter!` late-bind hook (rf2-uo7v) — this fn
  remains as a public surface for non-bundled adapters."
  [set-hiccup-emitter!-fn]
  (set-hiccup-emitter!-fn render-to-string)
  nil)
