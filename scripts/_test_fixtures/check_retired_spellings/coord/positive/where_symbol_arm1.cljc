(ns fixture.coord.positive.where-symbol-arm1
  "POSITIVE fixture (d/symbol): the `arm1.*` half of the retired prototype
  coordinate. `arm1.mount` is a NAMESPACE (dotted); the pervasive in-tree
  `arm1/some_test` shorthand names a FILE and is a negative — see
  coord/negative/bench_path_and_dir_shorthand.cljc.")

(defn mount-root! [el]
  (fail! :rf.error/no-frame-context
         'arm1.mount/render!
         "A Hicasso boundary rendered with no frame in scope."
         :mount-under-a-frame
         {:el el}))
