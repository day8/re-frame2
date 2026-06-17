(ns fixture.negative.keyword-in-comment
  "NEGATIVE fixture: the retired bad shape mentioned in `;;` line comments.
  Mirrors re-frame.error's rationale comment, which literally quotes the
  retired forms. Comments are masked, so MUST stay green."
  (:require [re-frame.error :as error]))

;; The retired hand-rolled forms made the keyword the WHOLE message:
;;   (ex-info ":rf.error/foo" {…})
;;   (ex-info (str error-kw) {…})
;;   (ex-info (str (:rf.error/id payload)) payload)
;; All route through the central builder now.
(defn boom [error-id reason]
  (error/throw-error! error-id 'rf/x reason))
