(ns re-frame.story.play.browser
  "Browser-tier assertion oracles — visual snapshot, axe-style a11y, and
  structural a11y (NewTestStory rf2-5x1wt.28,
  `tools/story/spec/017-Testing-Story.md` §Visual, a11y, and browser
  checks + §Canonical P1 assertions).

  ## Not a separate visual-testing system

  Spec/017 §Visual, a11y, and browser checks: *visual, a11y, and browser
  checks are NOT a separate testing system; they are runner-tiered
  assertions on the same Story variant or inline plan.* This ns is the
  executor for those richer assertions — it produces the ONE assertion
  record (`re-frame.story.result` shape, the `.19` boundary) every other
  assertion produces, so a visual/a11y finding rides the EXISTING
  assertion-record accumulator, NOT a parallel result vocabulary. It adds
  no new run-result slot; a finding is an assertion record like any other.

  ## Reuse the existing hooks (§C4 item 2 — prefer reuse, minimal)

  Two seams already exist in the tree; this ns REUSES them rather than
  adding a differ / a second axe loader:

  - VISUAL — `re-frame.story.identity` `snapshot-identity` /
    `content-hash` is the canonical visual-regression KEY (stable across
    hosts; the MCP `snapshot-identity` tool already surfaces it). The
    `:rf.assert/visual-snapshot` finding records that key as the snapshot
    IDENTITY. A real-browser pixel diff requires `:pixels` (a screenshot +
    differ that lands with the browser runner); the headless/JVM contract
    here is the fail-closed `:cannot-run` refusal.
  - A11Y (axe-style) — `re-frame.story.ui.a11y` runs axe-core in-browser
    and stores violations in `violations-by-frame` (the same atom the MCP
    `read-a11y-violations` tool reads). The `:rf.assert/a11y` finding projects those
    violations. axe-core is CLJS/browser-only (`:a11y-engine`); the
    headless/JVM contract is `:cannot-run`. This ns does NOT `:require` the
    CLJS-only UI ns into this `.cljc` (which would break the JVM classpath
    and the production-bundle isolation). Instead the a11y panel REGISTERS
    its violations-reader into the shared late-bound seam
    (`register-a11y-reader!`) at load time, and the evaluator reads through
    it (`live-a11y-violations`) — a one-way, opt-in facade, the same
    posture as the story-mcp `cljs-resolve` seam. The caller MAY also
    supply `:violations` directly (the runner reads the atom and threads
    it), keeping the evaluator itself pure.

  ## Structural a11y rides `:hiccup` (the JVM-testable rung)

  `:rf.assert/a11y-structural` is the spec's *structural a11y checks MAY
  require only `:hiccup`* rung. It inspects the rendered hiccup TREE (data)
  for structural accessibility facts — an `:img` with no `:alt`, an
  interactive control with no accessible name, a positive `:tabIndex`, a
  duplicate `:id` — WITHOUT a real browser or the axe engine. Because the
  hiccup tree is pure data, `eval-structural-a11y` is fully JVM-testable
  and the `:hiccup` runner satisfies it (`#{:hiccup-structure}` in the
  requirement registry).

  ## Fail-closed: headless returns `:cannot-run`, never a silent pass

  The capability gate (`re-frame.story.requirements`) is the single
  authority on WHICH runner may attempt an assertion; under a headless
  runner a `:pixels` / `:a11y-engine` requirement refuses there. This ns's
  browser-tier evaluators add a SECOND fail-closed guard at the executor
  itself: when the browser environment is unavailable (`browser-available?`
  false — JVM, node-runtime, or a CLJS REPL with no `js/window`) the
  visual / axe-a11y evaluators return a `:cannot-run` record rather than
  fabricating a pass. This is the testable contract the bead pins: a
  headless run returns `:cannot-run` for browser-tier assertions.

  ## Pure / JVM-testable

  Every evaluator is data → data: the visual evaluator takes the snapshot
  identity (or computes it from variant inputs); the a11y evaluator takes
  the violations vector (read from the live atom by the late-bound facade,
  or supplied directly); the structural evaluator takes the hiccup tree.
  The only impurity is `browser-available?` (a host probe) and
  `live-a11y-violations` (a late-bound atom read), both isolated so the
  evaluators stay testable on the JVM with no host."
  (:require [clojure.string            :as str]
            [re-frame.story.assertions :as assertions]))

;; ===========================================================================
;; ENVIRONMENT PROBE  (the fail-closed guard at the executor)
;; ===========================================================================

(defn browser-available?
  "True iff a REAL browser environment is reachable (a `js/window` with a
  document). JVM → false; CLJS node-runtime → false; CLJS in a browser →
  true. The visual / axe-a11y evaluators consult this and return
  `:cannot-run` when it is false — the second fail-closed guard (the first
  is the capability gate in `re-frame.story.requirements`). Mirrors
  `re-frame.story.play.dom/dom-available?` but additionally requires a
  `js/window` (the pixel/a11y engines need the full window, not just a
  detached document)."
  []
  #?(:clj  false
     :cljs (boolean
             (and (exists? js/window)
                  (exists? js/document)
                  (some? (.-querySelector js/document))))))

;; ===========================================================================
;; LATE-BOUND a11y HOOK  (reuse `re-frame.story.ui.a11y`, no hard require)
;; ===========================================================================
;;
;; `re-frame.story.ui.a11y` is CLJS-only (`.cljs`); a `.cljc` production ns
;; MUST NOT `:require` it (it would break the JVM classpath and leak the UI
;; into production bundles). The dependency points the WRONG way for a hard
;; require — the executor is below the UI. So the seam is INVERTED and
;; one-way: the a11y panel REGISTERS a violations-reader fn here at load
;; time (`register-a11y-reader!`), and the evaluator reads through it. A
;; JVM / headless build / a browser that never opened the panel leaves the
;; reader nil and the evaluator falls back to the supplied `:violations`
;; (or `:cannot-run`). This mirrors the story-mcp `cljs-resolve` posture
;; (read the CLJS-side surface without a compile-time dependency) without
;; relying on `resolve` (unavailable at CLJS runtime).

(defonce ^{:doc "The late-bound axe-violations reader the a11y panel
                installs via `register-a11y-reader!`. `frame-id` → violations
                vector (or nil). nil until the panel registers it."}
  a11y-reader
  (atom nil))

(defn register-a11y-reader!
  "Install the axe-violations reader `reader-fn` (`frame-id` → violations
  vector). The CLJS a11y panel (`re-frame.story.ui.a11y`) calls this at
  load time with a fn that reads its `violations-by-frame` atom — the
  one-way seam that lets this below-the-UI executor reuse the existing axe
  hook WITHOUT a compile-time dependency on the CLJS-only UI ns. Idempotent
  (last writer wins)."
  [reader-fn]
  (reset! a11y-reader reader-fn)
  nil)

(defn live-a11y-violations
  "The axe-core violations the in-browser a11y panel
  (`re-frame.story.ui.a11y`) accumulated for `frame-id`, or nil when no
  reader has been registered (JVM, a headless build, or a browser that
  never opened the panel). Reads through the late-bound `a11y-reader` seam
  — this ns does NOT `:require` the CLJS-only UI ns. Returns a vector of
  axe violation maps (`:id` / `:impact` / `:help` / `:nodes`) or nil."
  [frame-id]
  (when-let [reader @a11y-reader]
    (try (reader frame-id)
         (catch #?(:clj Throwable :cljs :default) _ nil))))

;; ===========================================================================
;; THE CANNOT-RUN FINDING  (rides the ONE assertion-record shape)
;; ===========================================================================

(defn- cannot-run-finding
  "A browser-tier finding the executor could NOT prove — the second
  fail-closed guard. Rides the ONE assertion-record shape (`:assertion` /
  `:passed?` / `:status` / `:reason`) so it accumulates with every other
  assertion; the `:status :cannot-run` makes it the distinct THIRD status
  (spec/017 §`:cannot-run`), never a silent pass."
  [assertion-id payload reason]
  {:assertion   assertion-id
   :payload     (vec payload)
   :passed?     false
   :cannot-run? true
   :status      :cannot-run
   :reason      reason})

;; ===========================================================================
;; VISUAL SNAPSHOT  (`:rf.assert/visual-snapshot`, requires :pixels)
;; ===========================================================================

(defn eval-visual-snapshot
  "Evaluate a `:rf.assert/visual-snapshot` assertion (spec/017 §Visual,
  a11y, and browser checks — requires `:browser` / `:pixels`). Returns the
  ONE assertion-record shape.

  `payload` is the assertion atom's args (`[]` or `[opts]`). `ctx` carries:

  - `:snapshot-identity` — the reused visual-regression key
    (`re-frame.story.identity/snapshot-identity`, the
    `{:variant-id :content-hash …}` record). This is the snapshot IDENTITY
    the finding records (reuse, not a new differ — §C4 item 2).
  - `:baseline` — an optional baseline snapshot record to diff against; when
    present the finding `:passed?` iff the content-hash matches.

  In a headless / JVM / node environment (`browser-available?` false) this
  returns `:cannot-run` — the testable contract: a headless run returns
  `:cannot-run` for browser-tier assertions. A real pixel diff lands with
  the `:pixels` browser runner; under that runner the content-hash identity
  is the regression key and `:baseline` mismatch is the failure."
  [payload {:keys [snapshot-identity baseline] :as _ctx}]
  (if-not (browser-available?)
    (cannot-run-finding assertions/id-visual-snapshot payload
                        (str "visual snapshot requires a real browser "
                             "(:pixels); the headless runner cannot capture "
                             "or diff a screenshot"))
    (let [hash      (:content-hash snapshot-identity)
          base-hash (:content-hash baseline)
          passed?   (or (nil? baseline) (= hash base-hash))]
      {:assertion assertions/id-visual-snapshot
       :payload   (vec payload)
       :passed?   passed?
       :status    (if passed? :pass :fail)
       :expected  (if baseline base-hash :rf.story/any-snapshot)
       :actual    hash
       :reason    (cond
                    (nil? baseline)
                    (str "captured visual snapshot identity " (pr-str hash))
                    passed?
                    (str "visual snapshot matches baseline " (pr-str base-hash))
                    :else
                    (str "visual snapshot " (pr-str hash)
                         " differs from baseline " (pr-str base-hash)))})))

;; ===========================================================================
;; AXE-STYLE A11Y  (`:rf.assert/a11y`, requires :a11y-engine)
;; ===========================================================================

(defn- violation-targets
  "The CSS selector(s) axe-core attached to a violation's `:nodes`, in
  document order (rf2-ffu8t — the SOURCE LINK a11y findings MUST carry,
  tools/story/spec/021 §4 + §5). Each axe node carries `:target` — a vector
  of CSS selectors pointing at the offending element(s); `re-frame.story.ui.
  a11y/record-violation-overlay!` already queries against exactly these to
  decorate the DOM. `select-keys [:id :impact :help]` DISCARDED `:nodes`,
  so the finding could only surface the rule id as its locus; this recovers
  the selectors so the result UI can link to the source element.

  Returns a flat vector of selector strings (deduped, order-preserving); an
  empty vector when the violation carries no `:nodes` / `:target`. Pure data
  → data; the violation is read as CLJS data (the live atom is normalised via
  `js->clj` before it reaches the executor), so this is JVM-testable."
  [violation]
  (into []
        (comp (mapcat (fn [node] (:target node)))
              (filter string?)
              (distinct))
        (:nodes violation)))

(defn- axe-finding
  "Project ONE axe violation into the finding map the result UI renders
  (rf2-ffu8t). Carries the axe surface (`:id` / `:impact` / `:help`) plus the
  recovered source-link selectors:

  - `:selector` — the FIRST target selector (the primary source link, nil
    when the violation carried no node target);
  - `:targets`  — every target selector (so a multi-node violation can list
    them all).

  Pure data → data."
  [violation]
  (let [targets (violation-targets violation)]
    (cond-> (select-keys violation [:id :impact :help])
      (seq targets) (assoc :selector (first targets)
                           :targets  targets))))

(defn eval-a11y
  "Evaluate an axe-style `:rf.assert/a11y` assertion (spec/017 §Visual,
  a11y, and browser checks — requires `:browser` / `:a11y-engine`). Returns
  the ONE assertion-record shape.

  `payload` is the assertion atom's args (`[]` or `[opts]`, where `opts`
  MAY carry `:max-impact` to fail only on violations at or above an impact
  level). `ctx` carries:

  - `:violations` — the axe-core violations vector to evaluate; when
    absent, the live panel atom is read via `live-a11y-violations` for
    `:frame-id` (reuse of the existing axe hook — §C4 item 2).
  - `:frame-id` — the variant frame whose live violations to read.

  In a headless / JVM / node environment (`browser-available?` false) this
  returns `:cannot-run` — axe-core is browser-only. Under the
  `:a11y-engine` browser runner it `:passed?` iff no violation (above the
  optional `:max-impact` floor) was found, projecting the violations into
  the finding's `:actual` so a failure reads diagnostically. This pairs the
  pixel/a11y finding with the variant frame (spec/017: SHOULD pair findings
  with app-db / args / trace / epoch evidence)."
  [payload {:keys [violations frame-id] :as _ctx}]
  (if-not (browser-available?)
    (cannot-run-finding assertions/id-a11y payload
                        (str "axe-style a11y requires a real browser "
                             "(:a11y-engine); the headless runner cannot run "
                             "axe-core"))
    (let [opts       (when (map? (first payload)) (first payload))
          max-impact (:max-impact opts)
          impact-rank {"minor" 0 "moderate" 1 "serious" 2 "critical" 3}
          floor       (get impact-rank max-impact 0)
          vs          (vec (or violations (live-a11y-violations frame-id) []))
          ;; a violation counts iff its impact is at or above the floor
          counted     (filterv (fn [v]
                                  (>= (get impact-rank (:impact v) 1) floor))
                                vs)
          passed?     (empty? counted)]
      {:assertion assertions/id-a11y
       :payload   (vec payload)
       :passed?   passed?
       :status    (if passed? :pass :fail)
       :expected  :no-a11y-violations
       :actual    (mapv axe-finding counted)
       :count     (count counted)
       :reason    (if passed?
                    (str "no axe-core a11y violations"
                         (when max-impact (str " at/above " (pr-str max-impact))))
                    (str (count counted) " axe-core a11y violation(s)"
                         (when max-impact (str " at/above " (pr-str max-impact)))))})))

;; ===========================================================================
;; STRUCTURAL A11Y  (`:rf.assert/a11y-structural`, requires :hiccup-structure)
;; ===========================================================================
;;
;; The pure-over-hiccup rung. It walks the rendered hiccup tree (data) for
;; structural accessibility facts a `:hiccup` runner can prove WITHOUT a
;; real browser / axe engine. The rules are a deliberately small, defensible
;; structural floor; richer semantic checks (colour contrast, computed
;; layout) genuinely need the browser / axe and belong to `:rf.assert/a11y`.

(defn- hiccup-element?
  "True iff `node` is a hiccup element vector `[tag & rest]` with a keyword
  tag. Pure data → data."
  [node]
  (and (vector? node) (pos? (count node)) (keyword? (first node))))

(defn- element-tag
  "The bare tag of a hiccup element, with any `#`-id / `.`-class suffix
  stripped (`:input.big#name` → `:input`). Pure data → data."
  [node]
  (let [t (name (first node))
        bare (-> t (str/split #"[.#]") first)]
    (keyword bare)))

(defn- element-attrs
  "The attribute map of a hiccup element, or `{}` when the second element
  is not a map. Pure data → data."
  [node]
  (let [a (nth node 1 nil)]
    (if (map? a) a {})))

(defn- element-children
  "The child nodes of a hiccup element (after the optional attribute map).
  Pure data → data."
  [node]
  (let [a (nth node 1 nil)]
    (if (map? a) (drop 2 node) (drop 1 node))))

(defn- has-text-child?
  "True iff the element has any non-blank string descendant (an accessible
  name from text content). Pure data → data."
  [node]
  (letfn [(walk [n]
            (cond
              (string? n) (not (str/blank? n))
              (hiccup-element? n)
              (some walk (element-children n))
              (sequential? n) (some walk n)
              :else false))]
    (boolean (some walk (element-children node)))))

(defn- accessible-name?
  "True iff `attrs` / `node` carry an accessible name — an `:alt`,
  `:aria-label`, `:aria-labelledby`, `:title`, or non-blank text content.
  Pure data → data."
  [attrs node]
  (boolean
    (or (some (fn [k] (let [v (get attrs k)]
                        (and (string? v) (not (str/blank? v)))))
              [:alt :aria-label :aria-labelledby :title])
        (has-text-child? node))))

(def ^:private interactive-tags
  "Tags that ALWAYS require an accessible name for a screen reader,
  regardless of attributes. `:input` is NOT here — it is conditional on
  its `:type` (see `input-needs-name?` / `interactive-needs-name?`): a
  text/checkbox/radio/etc. input needs a name, but a hidden / submit /
  reset / button / image input does not (those get their name from
  `:value` / `:alt`, or aren't rendered at all)."
  #{:button :a :select :textarea})

(def ^:private input-types-needing-no-name
  "`:input` `:type` values that do NOT require an explicit accessible name
  in the structural floor: `:hidden` is not rendered; `:submit` / `:reset`
  / `:button` carry a name via their `:value` (or a UA default); `:image`
  carries its name via `:alt` (already covered by `accessible-name?`).
  Every other input type (text, email, password, checkbox, radio, …) is a
  named control. Strings and keywords are both accepted (`:type \"text\"`
  and `:type :text`)."
  #{"hidden" "submit" "reset" "button" "image"})

(defn- input-needs-name?
  "True iff an `:input` element with `attrs` is a control that requires an
  accessible name — i.e. its `:type` is not one of the name-exempt types
  (`input-types-needing-no-name`). A `:type`-less input defaults to
  `\"text\"`, which DOES need a name. Pure data → data."
  [attrs]
  (let [t (:type attrs)
        t (cond (keyword? t) (name t)
                (string? t)  t
                (nil? t)     "text"        ; UA default type
                :else        (str t))]
    (not (contains? input-types-needing-no-name t))))

(defn- interactive-needs-name?
  "True iff a hiccup element with `tag` / `attrs` is an interactive control
  that requires an accessible name in the structural floor: an
  always-interactive tag (`interactive-tags`), OR an `:input` whose `:type`
  is not name-exempt (`input-needs-name?`). Pure data → data."
  [tag attrs]
  (or (contains? interactive-tags tag)
      (and (= :input tag) (input-needs-name? attrs))))

(defn- node-issues
  "Structural a11y issues for ONE hiccup element (not its descendants).
  Returns a vector of issue maps `{:rule … :tag … :detail …}`. Pure data →
  data. The rules are the structural floor:

  - `:img-missing-alt`         — an `:img` with no `:alt` attribute;
  - `:control-missing-name`    — an interactive control with no accessible
                                  name (no text / aria-label / title);
  - `:positive-tabindex`       — a `:tabIndex`/`:tab-index` > 0 (breaks the
                                  natural focus order)."
  [node]
  (let [tag   (element-tag node)
        attrs (element-attrs node)
        issues (cond-> []
                 ;; img must declare :alt (empty string is allowed — that is
                 ;; the explicit "decorative" signal; a MISSING :alt is the
                 ;; issue).
                 (and (= :img tag) (not (contains? attrs :alt)))
                 (conj {:rule :img-missing-alt :tag tag
                        :detail "img element has no :alt attribute"})

                 ;; interactive control needs an accessible name
                 (and (interactive-needs-name? tag attrs)
                      (not (accessible-name? attrs node)))
                 (conj {:rule :control-missing-name :tag tag
                        :detail (str (name tag) " control has no accessible name "
                                     "(no text content, :aria-label, or :title)")}))
        ;; positive tabindex (either spelling)
        ti (or (:tabIndex attrs) (:tab-index attrs))]
    (cond-> issues
      (and (number? ti) (pos? ti))
      (conj {:rule :positive-tabindex :tag tag
             :detail (str "positive :tabIndex " ti
                          " breaks the natural focus order")}))))

(defn structural-issues
  "Walk the rendered hiccup `tree` (data) and collect every structural a11y
  issue (`node-issues` per element, depth-first). Pure data → data — the
  JVM-testable core of `:rf.assert/a11y-structural`. Returns a vector of
  issue maps in document order."
  [tree]
  (letfn [(walk [n acc]
            (cond
              (hiccup-element? n)
              (let [acc' (into acc (node-issues n))]
                (reduce (fn [a c] (walk c a)) acc' (element-children n)))
              (sequential? n)
              (reduce (fn [a c] (walk c a)) acc n)
              :else acc))]
    (walk tree [])))

(defn eval-structural-a11y
  "Evaluate a `:rf.assert/a11y-structural` assertion (spec/017 §Visual,
  a11y, and browser checks — structural a11y MAY require only `:hiccup`).
  Returns the ONE assertion-record shape. Pure data → data — runs on the
  JVM under `clojure -M:test`.

  `payload` is the assertion atom's args (`[]` or `[opts]`). `ctx` carries
  `:hiccup` — the rendered hiccup tree (render-to-hiccup, the `:hiccup`
  runner's proof surface). The check `:passed?` iff `structural-issues`
  finds none; a failure projects the issues into `:actual` so the finding
  reads diagnostically. No browser is needed — the hiccup tree is data, so
  the `:hiccup` runner proves it."
  [payload {:keys [hiccup] :as _ctx}]
  (let [issues  (structural-issues hiccup)
        passed? (empty? issues)]
    {:assertion assertions/id-a11y-structural
     :payload   (vec payload)
     :passed?   passed?
     :status    (if passed? :pass :fail)
     :expected  :no-structural-a11y-issues
     :actual    issues
     :count     (count issues)
     :reason    (if passed?
                  "no structural a11y issues in the rendered hiccup tree"
                  (str (count issues)
                       " structural a11y issue(s) in the rendered hiccup tree: "
                       (str/join ", " (map (comp name :rule) issues))))}))

;; ===========================================================================
;; DISPATCH  (one entry the runner calls per browser-tier assertion atom)
;; ===========================================================================

(defn browser-assertion?
  "True iff `assertion-atom` is one of the browser-tier oracle assertions
  this ns evaluates (`re-frame.story.assertions/browser-assertion-ids`).
  Pure data → data — the runner uses this to route an atom here rather than
  to the dispatched `:rf.assert/*` handler path."
  [assertion-atom]
  (contains? assertions/browser-assertion-ids
             (assertions/assertion-atom-id assertion-atom)))

(defn eval-browser-assertion
  "Evaluate ONE browser-tier oracle assertion atom against `ctx`, returning
  the ONE assertion-record shape (spec/017 §Visual, a11y, and browser
  checks — runner-tiered assertions on the SAME result model). Routes by the
  atom's id:

  - `:rf.assert/visual-snapshot` → `eval-visual-snapshot` (`:pixels`)
  - `:rf.assert/a11y`            → `eval-a11y` (`:a11y-engine`)
  - `:rf.assert/a11y-structural` → `eval-structural-a11y` (`:hiccup`)

  `ctx` is the per-run context (`:snapshot-identity` / `:baseline` /
  `:violations` / `:frame-id` / `:hiccup`), supplied by the caller. A
  non-browser-tier atom returns nil (the caller routes it elsewhere)."
  [assertion-atom ctx]
  (let [id      (assertions/assertion-atom-id assertion-atom)
        payload (vec (rest assertion-atom))]
    (condp = id
      assertions/id-visual-snapshot (eval-visual-snapshot payload ctx)
      assertions/id-a11y            (eval-a11y payload ctx)
      assertions/id-a11y-structural (eval-structural-a11y payload ctx)
      nil)))
