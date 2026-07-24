(ns re-frame.freehand.client-only-views
  "The declared views the `FH-ROOT-008` row mounts — the interpreted
  `v/client-only` boundary and the root-scoped phase flip.

  They live apart from `re-frame.freehand.root-views` because the phase row
  needs a shape no other root row wants: a root carrying TWO client-only
  sites, so \"one update swaps every site\" is a claim the fixture can
  actually falsify. A view with one site would pass under a per-site
  implementation that tears.

  Host-neutral, like every root view: no subscriptions, no host objects, no
  React. The same declaration mounts to real DOM in the browser and answers a
  structural tree on the JVM."
  (:require [re-frame.freehand :as v]))

(v/defview two-sites
  "The phase-flip view: two client-only sites in ONE root, each with its own
  fallback and its own client subtree, and a shared heading OUTSIDE both.

  The heading is what makes the fallback assertions honest — it is identical
  in either phase, so a test that saw only the heading would pass on a root
  that rendered nothing at all.

  Each site's fallback and client subtree carry DIFFERENT text under the same
  id, so one selector answers which arm is live. Two sites, read together,
  answer whether the swap was atomic."
  [{:keys [left right]}]
  [:section#panel
   [:h1#heading "Dashboard"]
   (v/client-only {:fallback [:p#left "left-fallback"]}
     [:p#left (str "left-" left)])
   (v/client-only {:fallback [:p#right "right-fallback"]}
     [:p#right (str "right-" right)])])

(v/defview site-rooted
  "A view whose WHOLE body is one client-only site. The boundary node it
  produces must survive as its own node rather than being adopted into the
  view boundary's children — otherwise the marker that says *this is a
  fallback* is the thing that gets dropped."
  [_]
  (v/client-only {:fallback [:span.stand-in "stand in"]}
    [:span.live "live"]))
