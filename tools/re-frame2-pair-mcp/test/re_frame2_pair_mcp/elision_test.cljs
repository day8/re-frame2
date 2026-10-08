(ns re-frame2-pair-mcp.elision-test
  "The direct-read egress opts and the `:scalar-value` elision count.

  `egress-opts-edn` renders the opts map the direct-read eval forms hand to
  `re-frame.core/project-egress`. The server ships a boundary NAME, never
  resolved booleans: the framework resolves the name app-side. The form
  each tool ships is pinned in `re-frame2-pair-mcp.egress-elision-test`."
  (:require [cljs.test :refer-macros [deftest is]]
            [cljs.reader]
            [re-frame2-pair-mcp.tools.elision :as elision]
            [re-frame2-pair-mcp.tools.wire-pipeline :as wp]))

(deftest egress-opts-edn-names-the-boundary-and-overlays-the-size-override
  ;; Only the sensitive opt-in changes the boundary; `elision false`
  ;; (include-large? true) composes on top of it rather than swapping it,
  ;; so sensitive slots still redact under the size override.
  (doseq [[large? incl? expected]
          [[false false {:rf.egress/profile :rf.egress/off-box-tool}]
           [true  false {:rf.egress/profile :rf.egress/off-box-tool :rf.egress/include-large? true}]
           [false true  {:rf.egress/profile :rf.egress/local-raw}]
           [true  true  {:rf.egress/profile :rf.egress/local-raw :rf.egress/include-large? true}]]]
    (is (= expected (cljs.reader/read-string (elision/egress-opts-edn large? incl?)))
        (str "include-large? " large? ", include-sensitive? " incl?))))

(deftest scalar-value-arm-falls-back-to-walk-when-missing
  ;; read-sub passes no `:server-elided`, so its count comes from this walk.
  (let [marker {:rf.size/large-elided
                {:path [:user :uploaded-pdf] :bytes 102400 :type :string
                 :reason :schema :handle [:rf.elision/at [:user :uploaded-pdf]]}}]
    (is (= 1 (get-in (wp/run-wire-pipeline marker {:kind :scalar-value})
                     [:indicators :elided])))))
