(ns fixture.coord.negative.shipped-coordinates
  "NEGATIVE fixture: the shipped shapes rf2-hic-007 moved the corpus ONTO —
  a `:where` on the package's own namespace, and the corrected parity assertion
  that reads the PACKAGE prefix instead of one file's. If rule (d) fired on
  either, it would red the very fix it exists to protect.

  The corrected assertion is the interesting half: it is still a string, still
  compared with `str/starts-with?`, and still names a namespace prefix — so the
  rule cannot be 'a string that looks like a coordinate'. It has to be
  `front.`/`arm1.` specifically.")

(defn- fail-here! [id reason]
  (fail! id 're-frame.hicasso.impl.error/fail! reason :none {}))

(defn hydrate-root! [el]
  (fail! :rf.error/no-frame-context
         're-frame.hicasso.impl.mount/hydrate-root!
         "A Hicasso boundary rendered with no frame in scope."
         :mount-under-a-frame
         {:el el}))

(deftest a-shared-row-is-raised-by-the-runtimes-own-guard
  (is (seq (filter #(str/starts-with? (str (:where (:refuses %)))
                                      "re-frame.hicasso.impl.")
                   shared))
      "at least one row's refusal is raised by the runtime's own guard"))
