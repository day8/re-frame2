(ns re-frame.ui
  "Public surface of the re-frame.ui compiled-view substrate
  (artefact day8/re-frame2-ui; epic rf2-vxgfnd).

  S1a skeleton (rf2-vxgfnd.1): empty-but-loadable. The public grammar
  (defview, mount, create-root, ...) lands from S1b onward per the
  blessed surface table; anything not in that table does not exist.")

(def artefact-id
  "Skeleton classpath probe (rf2-vxgfnd.1). Asserted by
  `re-frame.ui.skeleton-cljs-test` to prove this artefact's src tree is
  on the cross-artefact classpath in both runtimes. Free to remove once
  the S1b compiler slice gives the test suite a real surface."
  :day8/re-frame2-ui)
