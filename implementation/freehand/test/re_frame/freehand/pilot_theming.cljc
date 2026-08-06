(ns re-frame.freehand.pilot-theming
  "THE THEMING PILOT — a component-library leaf built the two-plane way
  D018 rules, and the fixture the F5b acceptance is proved against.

  Everything here is CONSUMER code. It declares one view with `v/defview`,
  emits ordinary host values, and adds NO substrate machinery: there is no
  theme registry, no theme context protocol, no per-leaf token
  subscription, and — the point of the slice — no late tree transform. A
  theme is CSS, and a part is an address. Both ride the platform the host
  already runs.

  ## The two planes, and what carries each

  D018 splits theming into a portable STYLING plane and the existing
  structural composition plane. F5b lands the styling plane, and it is two
  mechanisms and no more:

  - **CSS TOKENS.** Visual values travel by the CSS cascade. An ancestor
    scope selects a named token bundle (`data-theme`) and inheritance
    reaches every descendant, so a theme switch re-renders one boundary,
    and nothing behind a memoized view immediately below it — no token
    subscription per leaf, no remount. The one value a stylesheet
    cannot know — a genuinely dynamic colour the caller supplies at the
    call site — rides as an inline namespaced custom property (`:accent`
    -> `--acme-chip-accent`). That is the whole token contract.

  - **SEMANTIC PART ADDRESSES.** A component declares a finite roster of
    public parts ([[part-ids]]) and emits each as a literal `data-part`
    value under a `data-component` scope, so a caller's stylesheet reaches
    a named region without a class contract and without colliding with
    another library's `label` or `control`. A part id is API: renaming or
    removing one is a breaking component-contract change even when the DOM
    stays visually identical. A private implementation node carries NO
    part id — the roster is a deliberate PUBLIC subset, not \"every node
    is addressable\".

  A component VARIANT — `:tone` here — is neither of those. It is an
  ordinary value prop rendered as a class, because it is a fixed choice
  the component anticipated, not an open address. Variants carry variants;
  they never carry part identity ([[variant-class]]).

  ## What this pilot is deliberately NOT

  It is not a theme service. Freehand invents no universal late `parts`
  transform and exports no re-theming verb; a library opts into this
  convention through its own props schema and part roster, exactly as this
  namespace does. Structural replacement — swap the dot for an icon, wrap
  the label in a popover — is the composition plane's job (children,
  compound children, `v/render-fn`/slot, a declared child), not a theme
  operation, and stays outside this fixture on purpose."
  (:require [re-frame.freehand :as v]))

;; ---------------------------------------------------------------------------
;; The library's public catalogue — the component id and its part roster
;; ---------------------------------------------------------------------------

(def component-id
  "The `data-component` marker that scopes this component's part names.
  A library decision: `acme.*` is this library's vocabulary, and the
  substrate reserves nothing for it."
  "acme/chip")

(def part-ids
  "The PUBLIC part roster, in emission order — the part schema a
  stylesheet, a structural test, a doc generator or an AI reads to learn
  every region it may address.

  It is a CLOSED set: a caller styles `root`, `dot` and `label`, and
  nothing else this component renders is addressable. Growing it is an
  additive API change; renaming or dropping one is a breaking change to
  every stylesheet that reached it."
  [:root :dot :label])

(def ^:private part-set (set part-ids))

(defn valid-part?
  "Is `id` a declared public part of this component?"
  [id]
  (contains? part-set id))

(defn part
  "The semantic part ADDRESS for `id` — the attribute fragment a component
  region merges to become reachable: `{:data-part \"label\"}`.

  The roster is the single source of truth, so an undeclared id is REFUSED
  here rather than silently emitting an address no stylesheet was told
  about and no test can enumerate. That refusal is the part contract with
  teeth: a typo in the component body fails at build/render, and a caller
  asking to address a part the component does not declare is told so with
  the roster in hand."
  [id]
  (when-not (valid-part? id)
    (throw (ex-info (str "re-frame.freehand.pilot-theming/part — " (pr-str component-id)
                         " declares no part " (pr-str id)
                         "; the public roster is " (pr-str part-ids))
                    {:component component-id :part id :roster part-ids})))
  {:data-part (name id)})

(defn variant-class
  "A component VARIANT lowered to a class. `:tone` is a value prop the
  component anticipated, so it rides a class — never a `data-part`, which
  would confuse a fixed choice with an open address."
  [tone]
  (str "acme-chip acme-chip--" (name (or tone :neutral))))

;; ---------------------------------------------------------------------------
;; The component — props only, two planes, one private node
;; ---------------------------------------------------------------------------

(v/defview chip
  "A small labelled status chip, styled entirely through the two planes.

  The root carries the `data-component` scope, the `root` part address,
  the variant class, and — only when the caller supplies the one value the
  cascade cannot know — an inline `--acme-chip-accent` custom property. The
  `dot` and `label` regions carry their part addresses and nothing that
  names a colour: their visual values are a stylesheet's to decide, live,
  through the token cascade an ancestor scopes.

  `:accent` is optional BECAUSE it is the dynamic-residue escape, not the
  default path; a chip that needs no per-instance accent emits no inline
  style at all and takes every value from the cascade."
  {:props [:map
           [:label :string]
           [:tone {:optional true} :keyword]
           [:accent {:optional true} [:maybe :string]]]}
  [{:keys [label tone accent]}]
  [:span (merge {:data-component component-id}
                (part :root)
                {:class (variant-class tone)
                 :style (when accent {:--acme-chip-accent accent})})
   [:span (part :dot)]
   [:span (part :label) label]
   ;; A PRIVATE implementation node: a decorative glow the caller neither
   ;; names nor styles. It carries a class for the library's own stylesheet
   ;; and NO part id, which is what makes the roster a public subset.
   [:span {:class "acme-chip__glow" :aria-hidden "true"}]])
