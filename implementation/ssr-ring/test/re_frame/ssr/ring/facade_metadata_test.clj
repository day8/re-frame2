(ns re-frame.ssr.ring.facade-metadata-test
  "The façade's re-exports carry their home var's `:doc` and `:arglists`; a
  bare `def` alias would drop them from REPL `doc` and editor hover."
  (:require [clojure.test :refer [deftest is]]
            [re-frame.ssr.ring]
            [re-frame.ssr.ring.lifecycle]
            [re-frame.ssr.ring.shell]))

(deftest facade-public-fns-carry-doc-and-arglists
  ;; One `import-fn` re-export, plus `default-on-error`, which copies its doc by hand.
  (is (= (select-keys (meta #'re-frame.ssr.ring.shell/default-html-shell) [:doc :arglists])
         (select-keys (meta #'re-frame.ssr.ring/default-html-shell) [:doc :arglists])))
  (is (= (:doc (meta #'re-frame.ssr.ring.lifecycle/default-on-error))
         (:doc (meta #'re-frame.ssr.ring/default-on-error)))))
