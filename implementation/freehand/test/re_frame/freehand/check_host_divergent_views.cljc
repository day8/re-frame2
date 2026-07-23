(ns re-frame.freehand.check-host-divergent-views
  "Two declarations whose bodies diverge across a `#?(:clj … :cljs …)`
  reader conditional — the checker's proof that it reads the branch the
  BROWSER build compiles, not only the branch the JVM reader elides to.

  `host-divergent` is the hazard the checker exists to catch: its `:clj`
  branch is a literal element (inside the grammar), while the `:cljs`
  branch the shadow-cljs build actually compiles has a DYNAMIC HEAD bound
  from props (`tag`) — outside the grammar. A checker that read only the
  `:clj` branch would report it compile-eligible and be wrong about the
  one target that matters, the SPA build.

  `host-uniform` is the control: both branches are literal elements, so
  they read DIFFERENTLY (the class names differ) yet are each inside the
  grammar — a genuinely-eligible reader-conditional view must stay
  eligible, so the both-branch reading cannot manufacture a false NO.

  Loaded on the JVM (a `.cljc` namespace, required by
  `re-frame.freehand.check-host-divergent-jvm-test`); the JVM reader sees
  the `:clj` branch at load time, so both views mount interpreted without
  incident. The checker reaches the `:cljs` branch by re-reading the source
  with the conditionals PRESERVED and selecting the branch itself — handing
  the reader `:features #{:cljs}` would not do it, because the JVM reader's
  `:clj` platform feature is always present and wins."
  (:require [re-frame.freehand :as v]))

(v/defview host-divergent
  "CLJ branch eligible; CLJS branch (the one the browser compiles) has a
  dynamic head bound from props."
  [{:keys [tag]}]
  #?(:clj  [:div "the JVM branch is a literal element - eligible"]
     :cljs [tag {} "the CLJS branch has a dynamic head - ineligible"]))

(v/defview host-uniform
  "Both branches are literal elements - eligible on either target, even
  though the two reads are not textually identical."
  [{:keys [label]}]
  #?(:clj  [:span.jvm label]
     :cljs [:span.spa label]))
