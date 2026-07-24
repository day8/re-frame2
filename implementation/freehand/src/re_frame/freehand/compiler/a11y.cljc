(ns re-frame.freehand.compiler.a11y
  "COMPILE-TIER accessibility diagnostics — the S4-C roster (rf2-74vlo;
  Spec 004 §Compile-tier warnings).

  Four `:rf.ui.compile/a11y-*` WARNINGS minted at `defview` expansion from
  LITERAL template-AST facts. They are warnings, never errors: a finding never
  fails a build. They ride the existing `env/warn!` accumulator and the existing
  print path, exactly like the three pre-existing compile warnings.

  ## The high-confidence charter (this namespace's whole design constraint)

  **A false positive is worse than a miss.** Every check fires only when the
  defect is PROVABLE from the local AST the compiler already built. The instant
  a shape introduces information the compiler cannot see — a dynamic child, a
  foreign component, a props spread, an aria value computed at runtime, an
  IDREF pointing somewhere else in the document — the check goes SILENT. There
  is no heuristic tier, no confidence score, no \"probably\" warning.

  Deliberate non-goals, so this file cannot grow into an a11y framework: no
  cross-view recursion (a child `defview`'s body is another view's business),
  no CSS or computed-style inference, no IDREF/document-wide analysis, no
  colour-contrast anything, no runtime checking, no rule plugins, and no
  configuration beyond the per-site suppression below. Spec 004 owns the
  \"re-frame2 is not an a11y framework\" boundary; this roster serves it.

  ## Suppression — rule-local, with a reason

  A finding is silenced by metadata on the offending literal form:

      ^{:rf.ui/suppress {:rf.ui.compile/a11y-click-non-interactive \"drag surface;
                                                                    keyboard path
                                                                    is the toolbar\"}}
      [:div {:on-click […]} …]

  The map's keys must be ids from this roster and every value a non-blank
  literal string — a malformed suppression is the compile error
  `:rf.ui.compile/bad-suppress`, never a silent no-op. There are no wildcards,
  no project-wide disables, no severity configuration, and no second config
  file. The metadata never becomes a DOM prop; nothing is stripped at runtime.

  ## Manifest evidence

  Every finding — suppressed or not — is recorded as a `:diagnostics` manifest
  site carrying the compiler-minted stable site id, so a suppressed finding
  stays an inspectable fact (with its reason) while printing nothing. Tools
  read it through [[re-frame.freehand.tool/view-manifest]]; nothing re-derives
  site identity downstream.

  Until rf2-hytu5 that last sentence named two things that did not exist:
  `structural-manifest` carried no `:diagnostics` key, and there was no
  `re-frame.freehand.tool` namespace at all. Findings were collected in the
  compiler env and reached no reader, which made a SUPPRESSED finding — whose
  entire purpose is to remain inspectable while silent — simply lost. Both
  ends now exist, and this docstring describes them."
  (:require [clojure.string :as str]
            [re-frame.freehand.compiler.env :as env]))

;; ---------------------------------------------------------------------------
;; The roster
;; ---------------------------------------------------------------------------

(def a11y-warning-ids
  "The four ids this namespace mints. Suppression metadata is validated
  against exactly this set."
  #{:rf.ui.compile/a11y-missing-accessible-name
    :rf.ui.compile/a11y-invalid-literal-aria
    :rf.ui.compile/a11y-click-non-interactive
    :rf.ui.compile/a11y-presence-exit-interactive})

;; ---------------------------------------------------------------------------
;; The ONE pinned WAI-ARIA table
;; ---------------------------------------------------------------------------

(def ^:private aria-attributes
  "WAI-ARIA 1.2 states and properties, as authored in this grammar (`aria-*`
  keywords pass through verbatim — `re-frame.freehand.rules/react-prop-name`).

  The value is the LITERAL value check, and only literal values are checked:

    :any      — free-form text / IDREF / token list: name-only validation
    #{…}      — a closed token set (booleans normalize to \"true\"/\"false\")
    :integer  — an integer
    :number   — a number

  Deprecated ARIA 1.2 attributes (`aria-dropeffect`, `aria-grabbed`) are
  listed: they are still valid names, and flagging them is a different
  (unruled) check."
  {:aria-activedescendant     :any
   :aria-atomic               #{"true" "false"}
   :aria-autocomplete         #{"inline" "list" "both" "none"}
   :aria-braillelabel         :any
   :aria-brailleroledescription :any
   :aria-busy                 #{"true" "false"}
   :aria-checked              #{"true" "false" "mixed" "undefined"}
   :aria-colcount             :integer
   :aria-colindex             :integer
   :aria-colindextext         :any
   :aria-colspan              :integer
   :aria-controls             :any
   :aria-current              #{"page" "step" "location" "date" "time"
                                "true" "false"}
   :aria-describedby          :any
   :aria-description          :any
   :aria-details              :any
   :aria-disabled             #{"true" "false"}
   :aria-dropeffect           #{"copy" "execute" "link" "move" "none" "popup"}
   :aria-errormessage         :any
   :aria-expanded             #{"true" "false" "undefined"}
   :aria-flowto               :any
   :aria-grabbed              #{"true" "false" "undefined"}
   :aria-haspopup             #{"false" "true" "menu" "listbox" "tree" "grid"
                                "dialog"}
   :aria-hidden               #{"true" "false" "undefined"}
   :aria-invalid              #{"grammar" "false" "spelling" "true"}
   :aria-keyshortcuts         :any
   :aria-label                :any
   :aria-labelledby           :any
   :aria-level                :integer
   :aria-live                 #{"assertive" "off" "polite"}
   :aria-modal                #{"true" "false"}
   :aria-multiline            #{"true" "false"}
   :aria-multiselectable      #{"true" "false"}
   :aria-orientation          #{"horizontal" "vertical" "undefined"}
   :aria-owns                 :any
   :aria-placeholder          :any
   :aria-posinset             :integer
   :aria-pressed              #{"true" "false" "mixed" "undefined"}
   :aria-readonly             #{"true" "false"}
   :aria-relevant             :any            ; a space-separated token LIST
   :aria-required             #{"true" "false"}
   :aria-roledescription      :any
   :aria-rowcount             :integer
   :aria-rowindex             :integer
   :aria-rowindextext         :any
   :aria-rowspan              :integer
   :aria-selected             #{"true" "false" "undefined"}
   :aria-setsize              :integer
   :aria-sort                 #{"ascending" "descending" "none" "other"}
   :aria-valuemax             :number
   :aria-valuemin             :number
   :aria-valuenow             :number
   :aria-valuetext            :any})

(def ^:private interactive-roles
  "ARIA roles that make a generic host element a real control — enough to
  silence `a11y-click-non-interactive`. Not a full role table: the check needs
  only \"did the author declare an interactive role?\"."
  #{"button" "link" "checkbox" "radio" "switch" "tab" "menuitem"
    "menuitemcheckbox" "menuitemradio" "option" "treeitem" "gridcell"
    "slider" "spinbutton" "textbox" "searchbox" "combobox"})

(def ^:private generic-tags
  "Host elements with no native interactive semantics — an `:on-click` on one
  of these is invisible to keyboard and assistive technology unless the author
  supplies role + focusability + key handling."
  #{:div :span :p :li :ul :ol :dl :dt :dd :section :article :header :footer
    :main :nav :aside :figure :figcaption :h1 :h2 :h3 :h4 :h5 :h6
    :table :thead :tbody :tfoot :tr :td :th
    :img :i :b :em :strong :small :pre :blockquote})

(def ^:private natively-interactive-tags
  "Host elements that are focusable and operable with no author effort — the
  exit-window population `a11y-presence-exit-interactive` cares about."
  #{:button :input :select :textarea :summary})

(def ^:private naming-attrs
  "Attributes that can supply an accessible name from somewhere this analysis
  deliberately does not follow. Presence of ANY of them — even with a dynamic
  value — silences the missing-name check."
  #{:aria-label :aria-labelledby :title})

;; ---------------------------------------------------------------------------
;; Props-AST readers (the analyzed shape, not the authored form)
;; ---------------------------------------------------------------------------

(defn- attr-entry
  "The analyzed attr entry for prop `k`, or nil. `:class` / `:style` / `:ref` /
  `:key` and `:on-*` handlers never appear here — they have their own slots."
  [props k]
  (first (filter #(= k (:k %)) (:attrs props))))

(defn- has-attr? [props k] (some? (attr-entry props k)))

(defn- literal-attr
  "The LITERAL value of prop `k`, or `::none` when absent or dynamic. `::none`
  is distinct from a literal `nil`."
  [props k]
  (if-some [a (attr-entry props k)]
    (if (:literal? a) (:value a) ::none)
    ::none))

(defn- spread?
  "A props spread makes the prop set open — the compiler cannot prove ANY
  attribute absent, so every check on this element goes silent."
  [props]
  (boolean (or (:spread props) (:safe-spread props))))

(defn- event-named?
  "Does the element carry a compiler-recognised `:on-<n>` handler? `:name` is
  the analyzed handler's prop name (\"on-click\", \"on-key-down\", …)."
  [props n]
  (boolean (some #(= n (:name %)) (:events props))))

(defn- truthy-literal?
  "A literal value React would render as a true boolean attribute."
  [v]
  (or (true? v) (= "true" v)))

(defn- presentational?
  "The element declares itself invisible to assistive technology — nothing to
  name, nothing to operate."
  [props]
  (or (truthy-literal? (literal-attr props :aria-hidden))
      (contains? #{"presentation" "none"} (literal-attr props :role))
      (truthy-literal? (literal-attr props :inert))))

;; ---------------------------------------------------------------------------
;; Suppression
;; ---------------------------------------------------------------------------

(defn suppressions
  "Validated `^{:rf.ui/suppress {<id> \"reason\"}}` metadata on `form`, as
  `{id reason}`. nil when absent. A malformed suppression is a compile error,
  never a silent no-op — a typo that quietly stops silencing a finding is the
  worst outcome this mechanism can produce."
  [e form]
  (when-some [m (:rf.ui/suppress (meta form))]
    (when-not (and (map? m) (seq m))
      (env/fail! e :rf.ui.compile/bad-suppress
                 (str "^{:rf.ui/suppress …} needs a non-empty literal map of "
                      "{<a11y warning id> \"reason\"}; got " (pr-str m))
                 {:form form :suppress m}))
    (doseq [[id reason] m]
      (when-not (contains? a11y-warning-ids id)
        (env/fail! e :rf.ui.compile/bad-suppress
                   (str "unknown suppression id " (pr-str id) " — suppressible "
                        "diagnostics are "
                        (str/join ", " (sort (map str a11y-warning-ids))))
                   {:form form :id id}))
      (when-not (and (string? reason) (not (str/blank? reason)))
        (env/fail! e :rf.ui.compile/bad-suppress
                   (str "suppressing " id " needs a non-blank literal reason "
                        "string saying WHY the finding is wrong here; got "
                        (pr-str reason))
                   {:form form :id id})))
    m))

;; ---------------------------------------------------------------------------
;; Finding emission
;; ---------------------------------------------------------------------------

(defn- report!
  "Record one finding. It ALWAYS becomes a `:diagnostics` manifest site (so a
  suppressed finding stays an inspectable fact carrying its reason); it prints
  only when unsuppressed."
  [e {:keys [id sid tag msg suppress]}]
  (let [reason (get suppress id)
        ;; `sid` is a delay: the vast majority of elements produce no finding,
        ;; and minting a lexical site id costs a digest. Findings on one element
        ;; share the one id.
        sid    @sid]
    (env/add-site! e :diagnostics
                   (cond-> {:sid sid :id id :tag tag :path (:path e)
                            :suppressed? (some? reason)}
                     (some? reason) (assoc :reason reason)))
    (when (nil? reason)
      (env/warn! e {:id id :msg (str msg " [site " sid "]") :sid sid}))
    nil))

;; ---------------------------------------------------------------------------
;; Check 1 — a11y-missing-accessible-name
;; ---------------------------------------------------------------------------
;;
;; Fires ONLY when a literal obvious control is PROVABLY nameless: every naming
;; route the compiler can see is absent AND its whole content subtree is
;; literal and provably text-free. One dynamic child, one foreign component,
;; one spread, one `v/html` — and the check goes silent, because the name may
;; well arrive from there.

(declare content-evidence)

(defn- element-content-evidence
  "`:name` / `:none` / `:unknown` for one literal element node's contribution
  to its ancestor's accessible name."
  [{:keys [tag props children html]}]
  (cond
    ;; trusted markup is opaque text
    (some? html)                 :unknown
    (spread? props)              :unknown
    ;; `<title>` inside an inline `<svg>` names the graphic
    (= :title tag)               :name
    ;; an `<img alt=…>` contributes its alt text (a dynamic alt might be "",
    ;; so treating presence as naming keeps us on the SILENT side)
    (and (= :img tag)
         (has-attr? props :alt)) :name
    (some (partial has-attr? props) naming-attrs) :name
    (presentational? props)      :none
    :else                        (content-evidence children)))

(defn- content-evidence
  "Fold a child-node vector into `:name` (some node provably contributes text)
  / `:none` (every node provably contributes nothing) / `:unknown` (at least
  one node is opaque to this analysis, so nothing is provable)."
  [nodes]
  (reduce
   (fn [acc n]
     (let [ev (case (:op n)
                :text     (if (str/blank? (str (:value n))) :none :name)
                :nothing  :none
                :element  (element-content-evidence n)
                :fragment (content-evidence (:children n))
                ;; :expr / :if / :for / :view / :foreign / :raw / :html /
                ;; :slot / :let / … — content this analysis cannot see
                :unknown)]
       (cond (= :name ev)                :name
             (= :name acc)               :name
             (or (= :unknown ev)
                 (= :unknown acc))       :unknown
             :else                       :none)))
   :none
   nodes))

(defn- check-missing-accessible-name!
  [e {:keys [tag props children html sid suppress]}]
  (let [named? (some (partial has-attr? props) naming-attrs)]
    ;; `(v/html s)` is hoisted out of `:children` onto the node: trusted markup
    ;; is opaque text, so the content is unknowable and the check goes silent.
    (when-not (or (spread? props) named? (presentational? props) (some? html))
      (cond
        ;; an <img> is named by @alt — including the deliberate empty alt of a
        ;; decorative image. NO alt at all is the provable defect.
        (and (= :img tag) (not (has-attr? props :alt)))
        (report! e {:id :rf.ui.compile/a11y-missing-accessible-name
                    :sid sid :tag tag :suppress suppress
                    :msg (str "<img> has no :alt — a screen reader announces the "
                              "file name, or nothing. Add :alt with the text the "
                              "image conveys, or :alt \"\" when it is purely "
                              "decorative (the explicit empty alt is the correct "
                              "spelling, not a missing one)")})

        ;; a <button>, and a real <a href>, are named by their CONTENT. The
        ;; classic defect is the icon-only control: literal markup with no text
        ;; anywhere in it.
        (and (or (= :button tag) (and (= :a tag) (has-attr? props :href)))
             (= :none (content-evidence children)))
        (report! e {:id :rf.ui.compile/a11y-missing-accessible-name
                    :sid sid :tag tag :suppress suppress
                    :msg (str "<" (name tag) "> has no accessible name — its "
                              "content is literal markup carrying no text (an "
                              "icon-only control), and it declares no :aria-label "
                              "/ :aria-labelledby / :title. Add :aria-label with "
                              "the ACTION it performs, e.g. {:aria-label "
                              "\"Close dialog\"}")})
        :else nil))))

;; ---------------------------------------------------------------------------
;; Check 2 — a11y-invalid-literal-aria
;; ---------------------------------------------------------------------------
;;
;; Literal `aria-*` NAMES are checked against the pinned table; literal VALUES
;; against that attribute's pinned token set / numeric kind. A dynamic value is
;; never judged — the compiler does not know what it evaluates to.

(defn- normalize-aria-value [v]
  (cond (true? v) "true" (false? v) "false" :else v))

(defn- invalid-aria-value
  "A didactic explanation when literal `v` is invalid for `k`, else nil."
  [k v]
  (let [spec (get aria-attributes k)
        v*   (normalize-aria-value v)]
    (cond
      (= :any spec) nil
      (set? spec)   (when-not (and (string? v*) (contains? spec v*))
                      (str "expected one of "
                           (str/join ", " (map pr-str (sort spec)))))
      (= :integer spec) (when-not (or (integer? v)
                                      (and (string? v) (re-matches #"-?\d+" v)))
                          "expected an integer")
      (= :number spec)  (when-not (or (number? v)
                                      (and (string? v)
                                           (re-matches #"-?\d+(\.\d+)?" v)))
                          "expected a number")
      :else nil)))

(defn- check-literal-aria!
  [e {:keys [tag props sid suppress]}]
  (doseq [{:keys [k value literal?]} (:attrs props)
          :when (and (keyword? k) (str/starts-with? (name k) "aria-"))]
    (if-not (contains? aria-attributes k)
      (report! e {:id :rf.ui.compile/a11y-invalid-literal-aria
                  :sid sid :tag tag :suppress suppress
                  :msg (str k " is not a WAI-ARIA attribute — assistive "
                            "technology ignores it entirely (React passes "
                            "aria-* through verbatim, so nothing else catches "
                            "the typo). Check the spelling against the ARIA "
                            "states and properties, e.g. :aria-labelledby, "
                            ":aria-describedby, :aria-expanded")})
      ;; a dynamic value is opaque — the NAME was still worth checking
      (when literal?
        (when-some [why (invalid-aria-value k value)]
          (report! e {:id :rf.ui.compile/a11y-invalid-literal-aria
                      :sid sid :tag tag :suppress suppress
                      :msg (str k " has the invalid literal value "
                                (pr-str value) " — " why
                                ". An unrecognised value is treated as if the "
                                "attribute were absent")}))))))

;; ---------------------------------------------------------------------------
;; Check 3 — a11y-click-non-interactive
;; ---------------------------------------------------------------------------
;;
;; A literal generic host element + a compiler-recognised `:on-click` + no
;; native and no LITERAL interactive role. Keyboard users and assistive
;; technology cannot reach it at all. The primary rewrite is the native
;; element, which is why the message names `:button` first.

(defn- check-click-non-interactive!
  [e {:keys [tag props sid suppress]}]
  (when (and (contains? generic-tags tag)
             (event-named? props "on-click")
             (not (spread? props))
             ;; ANY :role — even a dynamic one — means the author is steering
             ;; the semantics; we cannot prove it wrong.
             (not (has-attr? props :role))
             (not (presentational? props))
             ;; an explicitly focusable element with key handling is a
             ;; hand-rolled control: incomplete (no role) but deliberate.
             (not (and (has-attr? props :tab-index)
                       (or (event-named? props "on-key-down")
                           (event-named? props "on-key-up")
                           (event-named? props "on-key-press"))))
             (not (has-attr? props :content-editable)))
    (report! e {:id :rf.ui.compile/a11y-click-non-interactive
                :sid sid :tag tag :suppress suppress
                :msg (str ":on-click on <" (name tag) "> — a generic element is "
                          "not focusable and has no role, so keyboard and "
                          "assistive-technology users cannot activate it at "
                          "all. Use the native control: [:button {:on-click …} "
                          "…]. If the element must stay a <" (name tag) ">, give "
                          "it {:role \"button\" :tab-index 0} AND an "
                          ":on-key-down that handles Enter/Space")})))

;; ---------------------------------------------------------------------------
;; Check 4 — a11y-presence-exit-interactive
;; ---------------------------------------------------------------------------
;;
;; rf2-0ufty ruled presence DOM-AGNOSTIC and TIMEOUT-ONLY: the boundary stamps
;; no `inert` / `aria-hidden`, and a presence-aware CHILD owns its exit
;; accessibility by reading `(v/presence-phase)` = `:unmounting`. This check
;; is the compile-time counterpart of that author obligation, and its trigger
;; is a STRUCTURAL fact rather than a style judgement:
;;
;;   the presence runtime wraps each child ELEMENT in the phase-carrying
;;   context Provider (`presence_runtime/render-entries`), so inline literal
;;   markup under a presence boundary has its props evaluated in the PARENT's
;;   render — outside its own Provider. Such an element therefore PROVABLY
;;   cannot read its own phase, and no amount of author care can make it
;;   inert during its exit window without extracting a child view.
;;
;; It never blanket-warns on `v/presence`: it fires only on inline literal
;; FOCUSABLE markup. A `[toast-card {:key …}]` child — the idiomatic form, and
;; the one Spec 004 shows — is a `:view` node and is silent, because the child
;; view CAN read its phase and this analysis does not cross view boundaries.

(defn- literal-tabindex [props]
  (let [v (literal-attr props :tab-index)]
    (cond (integer? v) v
          (and (string? v) (re-matches #"-?\d+" v)) #?(:clj (Long/parseLong v)
                                                       :cljs (js/parseInt v 10))
          :else nil)))

(defn- exit-focusable?
  "Is this literal element provably focusable during its exit window?"
  [tag props]
  (and (not (spread? props))
       (not (presentational? props))
       (not (truthy-literal? (literal-attr props :disabled)))
       (not= -1 (literal-tabindex props))
       (or (contains? natively-interactive-tags tag)
           (and (= :a tag) (has-attr? props :href))
           (some-> (literal-tabindex props) (>= 0)))))

(defn- check-presence-exit-interactive!
  [e {:keys [tag props sid suppress]}]
  (when (exit-focusable? tag props)
    (report! e {:id :rf.ui.compile/a11y-presence-exit-interactive
                :sid sid :tag tag :suppress suppress
                :msg (str "<" (name tag) "> is focusable and authored INLINE "
                          "under (v/presence …). The presence boundary is "
                          "DOM-agnostic — it stamps no inert/aria-hidden — and "
                          "the phase Provider wraps the child ELEMENT, so this "
                          "markup's props are evaluated in the parent's render "
                          "and it provably cannot read its own "
                          "(v/presence-phase). It stays in the tab order for "
                          "the whole exit window. Extract a keyed child view "
                          "that stamps :inert / :aria-hidden against "
                          "(v/presence-phase) = :unmounting")})))

;; ---------------------------------------------------------------------------
;; Entry point
;; ---------------------------------------------------------------------------

(defn check-element!
  "Run the compile-tier a11y roster over one analyzed host element.

  `form` is the authored hiccup vector (the suppression-metadata carrier),
  `node` the element AST just built, and `sid-fn` mints the stable site id
  from an expression path — the compiler owns site identity; nothing
  downstream re-derives it.

  Custom elements (`tag` contains `-`) are skipped: their semantics come from
  a definition this compiler never sees, so no check over them can be
  high-confidence."
  [e form {:keys [tag custom? props children html] :as node} sid-fn]
  (let [suppress (suppressions e form)]
    (when-not custom?
      (let [ctx {:tag tag :props props :children children :html html
                 :sid (delay (sid-fn [:a11y])) :suppress suppress}]
        (check-missing-accessible-name! e ctx)
        (check-literal-aria! e ctx)
        (check-click-non-interactive! e ctx)
        (when (:presence-inline? e)
          (check-presence-exit-interactive! e ctx)))))
  node)
