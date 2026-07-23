(ns re-frame.freehand.web
  "The DOM-PLATFORM vocabulary namespace — the qualification that tells a
  reader, at the use site, that a property is a fact about a browser and
  not about the substrate.

  It holds no vars, and that is the point. Its whole job is to give the
  qualified desired-state keys an alias, so a declaration reads

      (ns my.app.menu
        (:require [re-frame.freehand :as v]
                  [re-frame.freehand.web :as web]))

      [:div {:popover :auto ::web/popover-open? open?}]

  rather than spelling the keyword out. The keys themselves are owned by
  [[re-frame.freehand.top-layer]]; the namespace exists so the `::web/`
  prefix is available to say what they are.

  D015 rules the qualification deliberately: the top layer is a
  DOM-platform capability, so its vocabulary must not read as neutral
  substrate grammar that some other renderer might be expected to honour.
  A key under this namespace is a browser key, visibly.

  Normative owner:
  [`spec/004-Views.md`](../../../../../spec/004-Views.md) §The DOM top
  layer.")
