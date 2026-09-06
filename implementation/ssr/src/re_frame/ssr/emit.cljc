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
  `parse-tag` runs `(name tag)`; UIx is not hiccup at all).
  A `.cljc` app sharing views across both — the point of the SSR story —
  therefore could not write a keyword head that meant one thing, and
  because the SERVER rendered it correctly the mistake survived every
  server-side test.

  Views are referenced by a CALLABLE head: the Var `reg-view` defs, or
  `(rf/view :id)`. Conventions §Render-tree shape vs runtime lookup owns
  this grammar; Spec 011's
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
;; NOT parsed as markup, so routing it through `escape-html` (which rewrites
;; `<`→`&lt;`, `>`→`&gt;`, `&`→`&amp;`, `"`/`'` → entities) CORRUPTS it — the
;; HTML parser never decodes character references inside a raw-text element,
;; so `[:style "a > b {…}"]` would ship the literal DOM text `a &gt; b` and
;; `[:script "if (a<b){…}"]` would ship `if (a&lt;b)`.
;;
;; Per rf2-xbvzh (ruling Option (a)): an ordinary inline `<script>`/`<style>`
;; with STRING content is AUTHOR CONTENT — trust the programmer. It is
;; emitted VERBATIM with only React's context-safe closing-sequence rewrite
;; (`html/escape-raw-text`), the ONE shared implementation this emitter, the
;; streaming walker, and the S5 serialiser (`re-frame.ssr.ui-tree`) all call,
;; so the same author content is byte-identical on every SSR path. This is
;; NOT sanitisation — the rewrite is the same breakout guard React applies,
;; aimed at attacker-supplied `</script>` DATA that must not terminate the
;; element early; residual JS/CSS-context safety is author-owned, as in
;; React. The DATA-payload channels are unchanged and keep their stricter
;; data-aware escapes: JSON-LD / structured `<head>` content via `reg-head`
;; (`re-frame.ssr.head.emit`, `<`→`<`) and the trusted host-shell
;; `:head`/`:body-end` opts. A `<script>`/`<style>` with no string children
;; (element-only or empty) is structurally inert and emits unchanged; the
;; raw-text set + escape live in `re-frame.ssr.html-helpers`
;; (`html/raw-text-tags` / `html/escape-raw-text`).

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
  (let [tag-expression (name tag-kw)
        ;; Match: tag-name optionally followed by #id and .class fragments.
        [_ tag id classes] (re-matches #"([^#.]+)(?:#([^.]+))?(.*)"
                                       tag-expression)
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
    (let [child-vector (vec children)]
      (clojure.string/join
        (map-indexed (fn [child-index child]
                       (emit-element child (when (zero? child-index) root-attrs)))
                     child-vector)))))

;; ---- source-coord annotation: NOT the emitter's job ----------------------
;;
;; The two dev-mode view annotations — `data-rf2-source-coord` and
;; `data-rf-view` — are stamped at the reg-view REGISTRATION boundary on
;; every host, NOT by this emitter (rf2-8vi4q). The JVM half is a
;; debug-gated hiccup walk wrapping the stored `:handler-fn` in
;; `re-frame.core/reg-view*`'s `:clj` branch (see
;; `re-frame.views.jvm-source-coord-annotation`); the CLJS half rides the
;; substrate wrappers. So a registered view reached through its callable
;; head (`[(rf/view :id) …]` / a Var — the shape isomorphic pages use)
;; arrives here ALREADY annotated, and this emitter just stringifies it.
;;
;; This is the ruled outcome of rf2-8vi4q. The rejected alternative
;; (Option A) stamped the annotation inside the emitter's keyword-view
;; branch — a branch rf2-j81hs deleted (a keyword head is a DOM element on
;; every host) and that never fired on the callable-head shape hydratable
;; pages actually contain. rf2-j81hs left the orphaned emitter-side
;; `format-view-source-coord` / `inject-coord-on-root-hiccup` fns for this
;; bead to delete; they are gone. The emitter is a pure hiccup → HTML
;; function with no annotation logic.

;; ---- root-attrs injection (per rf2-lxwse) --------------------------------
;;
;; The render-hash (data-rf-render-hash) is stamped on the first DOM-tag
;; element of the rendered tree. Historically this used a post-emit regex
;; replace on the output string; rf2-lxwse refactored that into a
;; structural injection on the hiccup root before stringification. The
;; injection threads an optional `root-attrs` map down through
;; `emit-element` and consumes it on the first DOM-tag emission — past any
;; fragments (`:<>`), Reagent-native heads (`:>`), or fn-headed /
;; view-ref components on the root path. Non-DOM-rooted trees silently
;; no-op on the injection (the same exemption the registration-boundary
;; source-coord annotation takes for a non-DOM root).

(defn merge-root-attrs
  "Merge root-level injected attrs (per rf2-lxwse) into the attrs map of
  a DOM tag. Existing attribute values win — the injected attr is only
  added when the key isn't already present, so a caller-supplied
  `data-rf-render-hash` on the root never gets overwritten."
  [attrs root-attrs]
  (reduce-kv (fn [merged-attrs attr-name attr-value]
               (if (contains? merged-attrs attr-name)
                 merged-attrs
                 (assoc merged-attrs attr-name attr-value)))
             attrs
             root-attrs))

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
  the arm.

  `el` crosses `error/safe-form` FIRST (rf2-9s68n) — see
  `re-frame.error` §cycle-safe diagnostic printing. `head` needs no such
  crossing: this arm is
  reached only for a keyword in the reserved `:rf/*` namespace."
  [element head]
  (let [safe-element (error/safe-form element)]
    (error/throw-error!
      :rf.error/invalid-hiccup-head
      'rf.ssr/emit
      (str "hiccup vector head " (pr-str head)
           " (in element " (pr-str safe-element) ") is in the framework-reserved"
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
                  :element safe-element}})))

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
  same malformed shape identically.

  rf2-9s68n — THIS ARM IS WHERE A FOREIGN HEAD LANDS, and it is the arm the
  defect was reported against: a React context provider is neither
  `keyword?` nor `ifn?`, so `[ctx.Provider {…}]` falls here, and `pr-str` of
  a self-referential JS object blew the stack — `RangeError` instead of the
  message this function exists to produce. `element` crosses
  `error/safe-form` first; `(first element)` is read from the crossed value,
  so the head is covered by the same one crossing."
  [element]
  (let [safe-element (error/safe-form element)]
    (error/throw-error!
      :rf.error/invalid-hiccup-head
      'rf.ssr/emit
      (str "hiccup vector head " (pr-str (first safe-element))
           " (in element " (pr-str safe-element) ") is not a valid hiccup head — a head"
           " must be a keyword (DOM tag / :<> / :> /"
           " :rf/suspense-boundary) or a callable component (fn / Var). A"
           " string / nil / number / boolean / collection head has no HTML"
           " interpretation; emitting its EDN form raw would bypass output"
           " escaping (XSS). Produce a valid hiccup head.")
      {:recovery :use-a-keyword-or-callable-hiccup-head
       :extra    {:head    (first safe-element)
                  :element safe-element}})))

#?(:clj
   (defn- declared-fixed-arities
     "The set of FIXED arities the compiled fn `f` declares.

     Read off the class, not discovered by calling: a Clojure fn compiles to
     a class declaring one `invoke` method per fixed arity. A purely variadic
     `(fn [& xs] …)` declares NONE, so this returns `#{}` for it — the
     variadic tail is `variadic-required-arity`'s business, and the two must
     stay separate so \"accepts any arity\" is never read as \"accepts zero\".

     Only ever called on a `fn?` value (`resolve-component-head` guards),
     which matters: a Var is `ifn?` but not `fn?`, and `clojure.lang.Var`
     declares `invoke` for arities 0–21 regardless of what it holds, so
     inspecting one would answer yes to everything."
     [f]
     (into #{}
           (keep (fn [^java.lang.reflect.Method m]
                   (when (= "invoke" (.getName m))
                     (alength (.getParameterTypes m)))))
           (.getDeclaredMethods (class f)))))

#?(:clj
   (defn- variadic-required-arity
     "The number of params before the `&` when `f` is VARIADIC, else `nil`.
     A variadic fn extends `clojure.lang.RestFn`, whose `getRequiredArity`
     gives that count: `(fn [& xs] …)` reports 0, `(fn [a & xs] …)` reports 1."
     [f]
     (when (instance? clojure.lang.RestFn f)
       (.getRequiredArity ^clojure.lang.RestFn f))))

#?(:clj
   (defn- form-2-invocation-arity
     "How many of the component's `argument-count` args to hand
      `inner-render-fn` — modelling which arm the SAME function, compiled by
      ClojureScript, would SELECT — or `nil` when no declared arm fits, in
      which case the JVM passes all arguments and lets its own `ArityException`
      report the real call.

     `nil` covers two situations that are NOT the same, and only one of them
     is host agreement (rf2-mocn3, mayor ruling 2026-09-01):

       • TOO MANY args for every arm of a multi-arm inner — the compiled
         CLJS dispatcher throws `Invalid arity: <count>` as well, so both
         hosts refuse and the shared component is rejected identically.
       • TOO FEW args — CLJS does NOT refuse. JavaScript binds the missing
         parameters to `undefined` and the render proceeds. The JVM refuses
         instead, DELIBERATELY. See `invoke-form-2-render-fn` for the
         supported contract and why this direction is not emulated.

     rf2-mocn3 (audit) — this used to walk every declared arity downward and
     take the longest accepted prefix, which is NOT what a compiled CLJS fn
     does. Only a fn with a SINGLE fixed arity and no variadic tail compiles
     to a bare JavaScript function, and only a bare JavaScript function drops
     extra arguments. Anything with more than one arm compiles to a dispatcher
     that switches on `arguments.length` and throws on an unsupported arity when no
     arm matches. Measured on node against `cljs.core/apply`, which is exactly
     what the `:cljs` branch of `invoke-form-2-render-fn` calls:

       (fn [x] …)                  at 3 args  → returns (extra args dropped)
       (fn ([x] …) ([x y] …))      at 3 args  → throws `Invalid arity: 3`
       (fn ([] …) ([x] …))         at 2 args  → throws `Invalid arity: 2`

     The prefix walk selected 2 and 1 for those last two and rendered happily,
     so a shared `.cljc` Form-2 component could render on the server and blow
     up on hydration — the precise parity this helper exists to hold.

     So the three selection rules mirror the three shapes the dispatcher has:

       1. an EXACT fixed arm for `argument-count`  → `argument-count`
       2. a variadic arm whose required count is
          satisfied by `argument-count`            → the whole arg list
       3. exactly ONE fixed arity, no variadic
          tail, shorter than `argument-count`      → that arity (truncate)

     Rule 3 is deliberately narrow, and widening it would be a DIFFERENT
     behaviour wearing this name. There is deliberately no fourth rule for
     TOO FEW args: nothing here ever SUPPLIES an argument the caller did not
     pass, so an inner that declares only larger arities falls through to
     `nil` and the JVM raises. That is the one place the hosts disagree, and
     `invoke-form-2-render-fn` states the contract and the reason."
     [inner-render-fn argument-count]
     (let [fixed-arities  (declared-fixed-arities inner-render-fn)
           required-arity (variadic-required-arity inner-render-fn)]
       (cond
         (contains? fixed-arities argument-count)
         argument-count

         (and required-arity (>= argument-count required-arity))
         argument-count

         (and (nil? required-arity)
              (= 1 (count fixed-arities))
              (< (first fixed-arities) argument-count))
         (first fixed-arities)

         :else nil))))

(defn- invoke-form-2-render-fn
  "Invoke a Form-2 inner render fn with `args`, on the JVM under the arity
  rules the compiled CLJS `inner` would follow for arm SELECTION and for
  EXCESS arguments — see THE SUPPORTED CONTRACT below for the one direction
  in which the JVM deliberately does NOT follow them. The idiomatic
  Reagent/UIx Form-2 inner either takes the SAME args as the outer
  (`(fn [x] …)`), ignores them and closes over the outer's (`(fn [] …)`), or
  — just as validly — takes a non-zero PREFIX of them (`(fn [kept] …)` under
  an `(fn [kept ignored] …)` outer). Those all compile to a bare JavaScript
  function, which drops extra arguments, so the client renders them; the JVM
  is strict, so this helper picks the call shape rather than being told it.
  What it must NOT do is be MORE permissive than the client, which is why
  `form-2-invocation-arity` models the compiled dispatcher rather than
  helpfully finding some arity that works.

  rf2-mocn3 — it used to DISCOVER the shape instead, with
  `(try (apply inner args) (catch ArityException _ (inner)))`, and that is
  wrong twice over. The catch enclosed execution of programmer code, so an
  `ArityException` raised INSIDE a correctly-invoked render (an ordinary
  wrong-arity bug in a helper it calls) was indistinguishable from an
  invocation mismatch: the render body ran a SECOND time, duplicating the
  effects of a non-pure render and reporting the retry's outcome instead of
  the original failure — or, for a variadic inner, succeeding at arity zero
  and silently shipping different HTML. And the retry tried only arity ZERO,
  so a prefix-taking inner was rejected on the server while rendering fine in
  the browser.

  So: select the shape from the inner's DECLARED arities
  (`form-2-invocation-arity`), then invoke exactly once. No user code runs
  inside a catch here, and anything the render throws propagates unchanged.
  When no declared arm fits, `(apply inner args)` lets the inner's own
  `ArityException` report the real call rather than a fabricated retry.

  THE SUPPORTED CONTRACT — stated narrowly, because the wide version is
  false (rf2-mocn3, mayor ruling 2026-09-01). This helper matches compiled
  CLJS on arity SELECTION (which arm of a multi-arm inner runs, and that a
  count no arm declares is refused on both hosts) and on EXCESS arguments (a
  single-fixed-arity inner compiles to a bare JS function, which drops them;
  the JVM truncates to match). It deliberately DIVERGES in one direction:

    MISSING arguments. Where the caller passes FEWER args than the inner's
    shortest arm requires, JavaScript binds the absent parameters to
    `undefined` and CLJS renders; the JVM raises `ArityException` and SSR
    fails. The server is therefore STRICTER than the client here — a legal
    shared `.cljc` Form-2 can render in the browser and fail during SSR.

  That is a choice, not an oversight, and the reason is one clause: CLJS
  supplying `undefined` is an accident of the JavaScript calling convention,
  and emulating it would ship an author's arity mistake as production HTML
  with nil props instead of failing where it can be seen. So do NOT \"fix\"
  this by filling the missing slots with nil. Both cases are pinned on both
  hosts, divergence included, by `re-frame.ssr.form2-arity-cljs-test`."
  [inner-render-fn args]
  #?(:clj  (let [invocation-arity (form-2-invocation-arity inner-render-fn
                                                            (count args))]
             (apply inner-render-fn
                    (if invocation-arity
                      (take invocation-arity args)
                      args)))
     :cljs (apply inner-render-fn args)))

(defn resolve-component-head
  "Resolve a callable-head hiccup component (`[component & args]`, where
  `component` is a fn or a Var — the idiomatic Reagent/UIx SSR shape,
  the same shape the render-tree hash walker supports) to renderable hiccup.

  A Form-1 component returns hiccup directly. A Form-2 component returns an
  INNER render fn; per Reagent/UIx Form-2 semantics it is invoked once
  more with the SAME args (at the arity its declared arms select, and
  tolerating EXCESS args only — see `invoke-form-2-render-fn` for the
  supported contract) to obtain the hiccup. Resolving Form-2 here
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
     ;; A keyword or symbol CHILD is spelled by its `name` — no leading
     ;; colon, namespace dropped (rf2-53lsj).
     ;;
     ;; These used to fall through to `escape-html`, whose `(str s)`
     ;; keeps a keyword's colon and a symbol's namespace, so the JVM
     ;; emitted `<card>:revenue</card>` and `<div>a/b</div>` where every
     ;; client substrate paints `<card>revenue</card>` and `<div>b</div>`.
     ;; Measured on both hosts: Reagent routes a `named?` child through
     ;; `(name x)` before handing it to React, which is why the namespace
     ;; disappears — `:a/b` and `'a/b` both paint `b`.
     ;;
     ;; rf2-j81hs aligned the HEAD meaning of a keyword and left the
     ;; CHILD spelling diverging; a text-node mismatch is not cosmetic,
     ;; because React hydration reconciles text nodes as well as element
     ;; structure, so the server's ":revenue" could not hydrate cleanly
     ;; against the client's "revenue". Same rule, same reason: one
     ;; render tree means one thing on every host.
     ;;
     ;; `(or (keyword? el) (symbol? el))` rather than `named?` — the
     ;; latter is CLJS-only, and this is a `.cljc` emitter.
     (or (keyword? el) (symbol? el))
     (html/escape-html (name el))
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
         ;; a supplied `:render-hash` lost its `data-rf-render-hash` even though
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
         ;; wraps the React component in a `reg-view` and references THAT
         ;; by its callable head — the Var `reg-view` defs, or
         ;; `(rf/view :id)`. rf2-ycz3k: this used to say the wrapping
         ;; reg-view was something "the SSR emitter resolves", which has
         ;; been false since rf2-j81hs made the emitter a pure
         ;; hiccup → HTML function with no registry lookup. Nothing
         ;; resolves an id here; the CALLABLE head is what the emitter
         ;; invokes, which is why the spelling has to be in the message.
         ;;
         ;; rf2-9s68n — `el` crosses `error/safe-form` before it is
         ;; printed OR put in ex-data. This arm is the one where a foreign
         ;; JS value is not merely possible but EXPECTED: `[:> ctx.Provider
         ;; …]` is what `:>` interop is FOR, and a React 19 provider is a
         ;; cyclic object graph.
         (= :> head)
         (let [el (error/safe-form el)]
           (error/throw-error!
             :rf.error/ssr-reagent-native-head
             'rf.ssr/emit
             (str "Reagent-native interop head `:>` "
                  "(element " (pr-str el) ") cannot be "
                  "rendered server-side — it targets a "
                  "React component and there is no React "
                  "on the JVM. Wrap the component in a "
                  "reg-view and reference that view by its "
                  "CALLABLE head — the Var reg-view defs "
                  "(`[my-view …]`) or `[(rf/view :my/id) …]` "
                  "— or render it client-only. A bare "
                  "keyword head is an HTML element, not a "
                  "view reference.")
             {:recovery :wrap-in-reg-view-or-render-client-only
              :extra    {:element el}}))

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
         ;; rf2-9s68n — `el` crosses `error/safe-form` first: a
         ;; boundary's `:fallback` is ordinary hiccup and can carry a
         ;; foreign JS value anywhere inside it.
         (= :rf/suspense-boundary head)
         (let [el (error/safe-form el)]
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
              :extra    {:element el}}))

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
         ;; tag)`; UIx are not hiccup at all). A `.cljc` app sharing
         ;; views across both — the whole point of the SSR story — could
         ;; not write a keyword head that meant one thing, and the server
         ;; rendered it CORRECTLY while the client painted a phantom, so
         ;; the mistake survived every server-side test (rf2-o4rbh found it
         ;; in the flagship streaming example).
         ;;
         ;; Conventions §Render-tree shape vs runtime lookup owns the head
         ;; grammar; Spec 011's
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
               ;; and a `[:SCRIPT "a<b"]` would classify wrongly. Normalise
               ;; for classification while preserving the author's emitted
               ;; case.
               normalised-tag-name (clojure.string/lower-case tag-name)
               void?        (contains? void-elements (keyword normalised-tag-name))
               raw-text?    (contains? html/raw-text-tags normalised-tag-name)]
           (cond
             void?     (str "<" tag-name (attr-string attrs) ">")
             ;; rf2-xbvzh — an ordinary inline <script>/<style> with STRING
             ;; content is author content: emit it VERBATIM with only the
             ;; shared closing-sequence rewrite (`html/escape-raw-text`),
             ;; byte-identical to the S5 serialiser and the streaming walker.
             ;; The `every? string?` gate mirrors the compiled path — an
             ;; all-string body is the real inline-script/style shape; any
             ;; structural child leaves the existing per-child walk untouched
             ;; (element children pass through inert; the hiccup emitter gains
             ;; no compiled child-shape grammar).
             (and raw-text? (seq children) (every? string? children))
             (str "<" tag-name (attr-string attrs) ">"
                  (html/escape-raw-text normalised-tag-name
                                        (clojure.string/join children))
                  "</" tag-name ">")
             :else
             (str "<" tag-name (attr-string attrs) ">"
                  (emit-children children)
                  "</" tag-name ">")))

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
  resolution, :doctype? prefix, and :render-hash root-element hash
  injection for client-side mismatch detection.

  Per rf2-lxwse: when `:render-hash` is supplied, `data-rf-render-hash`
  is threaded as `root-attrs` through `emit-element` and merged onto the
  first DOM-tag element of the rendered tree — past view-refs, fragments,
  Reagent-native heads, and fn-headed components on the root path. This
  replaces the prior post-emit regex-on-string injection: structural,
  composes with the source-coord annotation, and silently no-ops for
  non-DOM-rooted trees (matching the source-coord exemption).

  Per rf2-atmvj / rf2-i15nh: `:render-hash` is the ONE marker spelling.
  A caller that wants the marker computes the structural hash itself and
  passes it in — that single hash then drives BOTH the root-element
  `data-rf-render-hash` injection AND the caller's own payload slot
  (e.g. the ssr-ring pipeline's `:rf/render-hash`), so the tree is
  walked once rather than twice:

      (let [h (ssr/render-tree-hash tree)]
        {:html (ssr/render-to-string tree {:render-hash h})
         :rf/render-hash h})

  `:render-hash` is a pure pass-through to the root-attrs stamper, so
  Spec 011's hash/emit separation is preserved (no combined walker).
  The 1-arity is `(render-to-string tree {})` — no doctype, no marker.

  Unknown opts are ignored; the emitter does not validate its opts map."
  ([render-tree] (render-to-string render-tree nil))
  ([render-tree opts]
   ;; Per rf2-ezdwh — bind the per-render parse-tag-name memo so
   ;; repeated heads (`:div`, `:span`, `:p`, …) parse once instead of
   ;; once per emission. Cache lives only for the duration of this
   ;; render call.
   (binding [*tag-name-cache* (volatile! {})]
     (let [supplied-hash (:render-hash opts)
           root-attrs    (when supplied-hash
                           {:data-rf-render-hash supplied-hash})
           body          (emit-element render-tree root-attrs)]
       (if (:doctype? opts)
         (str "<!DOCTYPE html>" body)
         body)))))

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
;; one-time ns-load publications above. The React-shaped adapter (UIx)
;; clears its per-generation `emitter-cell` on `dispose-adapter!`
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
