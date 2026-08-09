(ns re-frame.migration.hicasso.donor
  "**What Reagent 2.0.1 does** — the DONOR column of the design's §3 table.

  Every rewrite in
  `docs/design/hicasso/studio/reagent-codemod-against-the-landed-escape.md`
  is argued from a proof sketch, and every proof sketch is a claim about
  this namespace's subject: what Reagent's `convert-prop-value` / `kv-conv`
  / `cached-prop-name` answer for a given prop. The claims are pinned by an
  executed suite — `codemod-contract-donor-*` in
  `implementation/adapters/reagent/test/re_frame/reagent_codemod_contract_donor_cljs_test.cljs`
  (rf2-d2mwk) — so a Reagent bump that moves any of them goes red with
  \"codemod\" in the failing test's name.

  ## The key function, and why it is not transcribed

  Reagent's `cached-prop-name` and this repo's
  [[re-frame.bench.hicasso.front.slot/prop-name]] are the SAME kebab→camel
  rule with the same three seeded renames and the same `aria`/`data`
  exemption. They part company in exactly two cells:

  | cell | Reagent's `cached-prop-name` | our `slot/prop-name` |
  |---|---|---|
  | a STRING key | verbatim — the cache is only consulted for `named?` keys, so `\"class\"` stays `\"class\"` | the three renames apply to every spelling, so `\"class\"` is `\"className\"` |
  | a `--custom-property` | mangled: `--brand-color` → `BrandColor` | preserved verbatim |

  So [[key-name]] is written as *the shared rule plus its two named
  deltas*, never as a second copy of the camel algorithm. That is not
  tidiness. The design's §9.5 — \"the tool reimplements `prop-name`, and
  nothing pins the two together\" — survived its own adversarial pass
  UNREPAIRED, and a transcribed camel rule here would be precisely the
  second implementation it warns about, drifting silently in both
  directions. There is one camel rule in this repository and this
  namespace calls it."
  (:require [clojure.string :as str]
            [re-frame.bench.hicasso.front.slot :as slot]))

(defn css-var-name?
  "Is `n` a CSS custom property? Reagent's `dash-to-prop-name` splits
  `\"--brand-color\"` into `(\"\" \"\" \"brand\" \"color\")` and the empty
  leading segments capitalize to nothing, yielding `BrandColor` — a style
  key nothing reads. Our rule preserves it and React routes it through
  `setProperty`, so it works.

  Preserving the donor here would mean writing a key that never worked, so
  the design refuses the cell and reports `:css-var-repair` (§4.2). This
  predicate exists to DETECT that cell; the mangled answer is never
  computed, because it is never written."
  [n]
  (str/starts-with? n "--"))

(defn key-name
  "Reagent's `cached-prop-name` answer for a literal map key — the React
  property name the donor emitted the value under.

  Returns `nil` where the donor's answer is one this tool refuses to
  preserve (a CSS custom property) or one it cannot model (a key that is
  neither keyword, symbol nor string — a number, say, which `gobj/set`
  stringifies). A `nil` means *do not rewrite this key*, never *the key
  emits nothing*."
  [k]
  (cond
    ;; Delta 1. `cached-prop-name` consults its cache only when the key is
    ;; `named?`; anything else is handed back untouched, seeded renames
    ;; included. Pinned by `codemod-contract-donor-w2-nested-map-keys`'s
    ;; "a STRING key is verbatim" row.
    (string? k) k

    (or (keyword? k) (symbol? k))
    (let [n (name k)]
      ;; Delta 2, detected and refused rather than reproduced.
      (when-not (css-var-name? n)
        ;; The cache is keyed on `(name k)`, so a namespaced spelling
        ;; lands on the same slot as its bare twin — `:x/class` is
        ;; `className`. Re-keywording `n` reproduces that.
        (slot/prop-name (keyword n))))

    :else nil))

(defn fixpoint-key?
  "Is `k` already spelled the way the donor emitted it, so that W2 has
  nothing to write? True when the destination's `clj->js` answer for the
  key already equals the donor's `cached-prop-name` answer."
  [k dest-name]
  (= dest-name (key-name k)))
