(ns re-frame.schemas-walker-large-test
  "`extract-large-paths-from-schema`, the `:large?` entry point other artefacts
  reach through the `:schemas/extract-large-paths-from-schema` late-bind hook.
  It shares the `:sensitive?` walker's traversal, whose path rules the
  `:sensitive?` tests pin; these pin the flag threading and `:hint`."
  (:require [clojure.test :refer [are deftest]]
            [re-frame.schemas :as rf.schemas]))

(deftest large-walker-claims-flagged-slots-with-their-hint
  (are [schema expected] (= expected (rf.schemas/extract-large-paths-from-schema schema []))
    [:map [:doc [:map [:attachment [:map [:payload {:large? true} :string]]]]]]
    {[:doc :attachment :payload] {:large? true :source :schema}}

    [:map [:upload {:large? true :hint "video-blob"} :string]]
    {[:upload] {:large? true :source :schema :hint "video-blob"}}

    ;; a :hint is inert unless the flag is exactly true
    [:map [:blob {:large? false :hint "x"} :string]]
    {}))
