(ns re-frame.hicasso.coord-sentinel-source
  "THE SENTINEL DECLARATIONS (rf2-hic-007) — one `defview` and one
  `defhost`, declared for their SOURCE COORDINATES and nothing else.

  ## Why they are not in the suite that asserts about them

  The first draft put them in
  `re-frame.hicasso.error-source-coord-elision-prod-test` itself, and the
  bundle scan went red on a build whose erasure was perfectly correct.
  `cljs.test` stamps `:file` and `:line` into the report map of every
  `deftest` and every `is`, so a test namespace's own file name is in the
  release bundle 38 times over before `defview` captures anything. A
  sentinel that fires on a green build is worse than no sentinel: it
  trains its reader to disbelieve it.

  So the declarations live in a namespace with no `deftest` in it. The
  ONLY thing that can put `coord_sentinel_source.cljs` in a release bundle
  is a `defview` or `defhost` coordinate surviving production erasure,
  which is exactly the claim
  `implementation/hicasso/scripts/check_source_coord_elision.cjs` makes.

  This file is compiled into the `:browser-test-prod-elision` build by the
  elision suite's `:require`, the same way `checkpoint_support` and
  `roots_frames_support` reach their suites. It registers nothing, mounts
  nothing and holds no state."
  (:require [re-frame.hicasso :as h]))

(h/defview sentinel-row
  "Declared for its coordinate. Under a dev build the macro registers this
  file's absolute path, line and column against [[view-name]]; under
  `:advanced` + `goog.DEBUG=false` it registers nothing and the path
  string is not in the artefact."
  [_]
  [:li "sentinel"])

(h/defhost sentinel-host
  "The declaration channel's half of the same proof — `defhost` opens the
  same extent under the same gate."
  (fn CoordSentinel [_props] nil)
  {:callbacks {:on-change :event}})

(def view-name
  "The ledger key for [[sentinel-row]] — the `\"<ns>/<sym>\"` string the
  macro also stamps as `displayName`, and the bundle scan's POSITIVE
  CONTROL. It is emitted unconditionally, so its presence is what proves
  the scan is reading a bundle that really compiled these declarations."
  "re-frame.hicasso.coord-sentinel-source/sentinel-row")

(def host-name
  "The ledger key for [[sentinel-host]]."
  "re-frame.hicasso.coord-sentinel-source/sentinel-host")
