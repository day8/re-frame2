(ns re-frame.hicasso.slot-corpus
  "THE CANONICAL SLOT CORPUS — authored prop key → the React slot it
  emits into.

  A support namespace: it defines NO `deftest`, so the lane-bijection
  gate's universe (files that evaluate a test-defining form at the top
  level) never reaches it and it needs no lane, no roster entry and no
  workflow change. It is required by
  [[re-frame.hicasso.codec-cljs-test]], which asserts the codec's caches
  answer the rule over every row.

  The table is one definition read by several suites on purpose: a rule
  written against ONE spelling is a rule the other spellings walk past,
  and a corpus that only carried bare kebab keywords could not tell.")

(def corpus
  "Authored prop key → the canonical React slot it emits into.

  ONE table, read by both hosts and by the codec's cache suite. Every
  branch of the rule is represented, and every spelling the codec accepts
  appears at least once — because a rule written against the spelling is
  a rule the other spellings walk past, and a corpus that only carries
  bare kebab keywords could not tell."
  {;; the three React renames — the RULE, so they hold for every spelling
   :class          "className"
   :className      "className"
   "class"         "className"
   :x/class        "className"
   'class          "className"
   :for            "htmlFor"
   "for"           "htmlFor"
   :x/for          "htmlFor"
   :charset        "charSet"
   'charset        "charSet"

   ;; kebab → camel
   :on-click       "onClick"
   :tab-index      "tabIndex"
   :on-mouse-enter "onMouseEnter"
   :default-value  "defaultValue"
   :a-b-c          "aBC"
   'on-click       "onClick"
   :x/on-click     "onClick"

   ;; a hump the author already wrote survives — this is why the rule
   ;; carries its own `capitalize` rather than `str/capitalize`, which
   ;; would lower-case the tail and hand React `Viewbox`
   :view-Box       "viewBox"
   :on-Click       "onClick"

   ;; nothing to camelCase
   :x              "x"
   :onInput        "onInput"
   :viewBox        "viewBox"
   :id             "id"
   :ref            "ref"
   :key            "key"

   ;; aria/data are HTML attribute names in React too
   :aria-label     "aria-label"
   :aria-hidden    "aria-hidden"
   :data-index     "data-index"
   :data-testid    "data-testid"
   'aria-label     "aria-label"
   :x/data-index   "data-index"

   ;; a CSS custom property is preserved verbatim
   :--gap          "--gap"
   :--my-custom-x  "--my-custom-x"

   ;; a string is already a React name and is taken verbatim, apart from
   ;; the three renames above — so `"on-input"` and `:on-input` are
   ;; DIFFERENT slots
   "on-input"      "on-input"
   :on-input       "onInput"
   "onInput"       "onInput"
   "data-x"        "data-x"
   "--gap"         "--gap"
   "aria-label"    "aria-label"

   ;; the prototype-poisoning names are the codec's problem, not the
   ;; rule's; the rule answers them like any other name
   :__proto__      "__proto__"
   :constructor    "constructor"})
