(ns re-frame.freehand.tool
  "The Freehand TOOL-TIER reader door — what an inspector reads a
  declaration's compile-time analysis through.

  One namespace, one question: **given a value, what did the compiler
  statically learn about it?** The answer is the manifest the compiled tier
  already builds — its subscription, event, render-slot, trusted-markup,
  committed-frame and boundary-crossing rosters, its capability set, its
  ViewCell verdict, and the compile-tier `:diagnostics` findings. Nothing here
  computes anything; every read is a projection of a value the declaration is
  already carrying.

  ## Why it is not `v/manifest`

  [[re-frame.freehand/manifest]] is the APPLICATION's read: it is asked about a
  view, by code that knows it has one. A tool is asked about whatever it is
  holding — a var it swept out of a namespace, an id it was handed over a wire,
  a value from a registry it does not own — and a reader that throws on the
  first non-view is a reader that cannot sweep. So [[view-manifest]] is TOTAL:
  every value has an answer, and `nil` means \"nothing statically known\",
  whether that is because the value is not a view at all or because it is an
  interpreted declaration with no analysis to report.

  That is the whole of the difference, and it is the whole of this namespace.

  ## What this door deliberately is NOT

  It is a READER, not a tool framework. There is no accumulator — the donor's
  per-occurrence evidence accumulator was ruled out permanently (rf2-drpa3.167,
  REPLACE), not merely deferred — no root registry, no interval log and no
  history store: retention is Spec 009's ring, and nothing here retains
  anything at all. Live reads over mounted occurrences are a separate slice
  (rf2-lvvl2) with a separate owner.

  Normative owner: [`spec/004-Views.md`](../../../../spec/004-Views.md);
  the instrumentation contract these reads serve is
  [`spec/009-Instrumentation.md`](../../../../spec/009-Instrumentation.md)."
  (:require [re-frame.freehand.descriptor :as descriptor]))

(defn view-manifest
  "The compile-time manifest `x` carries, or `nil` when there is none.

      (tool/view-manifest people-list)
      ;; => {:view-id       :app.people/people-list
      ;;     :grammar       :re-frame.freehand/v1
      ;;     :subscriptions [{:sid … :query [:person 7] :source-coord {…} :path [0]}]
      ;;     :events [] :slots [] :html-sites []
      ;;     :diagnostics   [{:sid … :id :rf.ui.compile/a11y-click-non-interactive
      ;;                      :tag :div :path [0] :suppressed? false}]
      ;;     :capabilities  #{:sub}
      ;;     :reactive?     true
      ;;     :view-cell     :present
      ;;     :crossings     [{:view-id … :lowering :compiled :source-coord {…} :path [1]}]}

  TOTAL over every value, which is what makes it a tool read rather than an
  application one: a sweep over a namespace's publics hands this function
  strings, numbers, plain functions and interpreted views, and each of those
  answers `nil` rather than throwing. `nil` means the same thing in every
  case — the compiler knows nothing statically about this value — and a caller
  that needs to tell an interpreted DECLARATION from a non-view asks
  [[re-frame.freehand/view?]], which is the predicate that question belongs to.

  The manifest is the declaration's own data, returned as-is. Its shape is
  documented once, on [[re-frame.freehand.compiler/structural-manifest]]."
  [x]
  (when (descriptor/view? x)
    (descriptor/manifest x)))
