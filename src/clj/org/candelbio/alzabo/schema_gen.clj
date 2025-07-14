(ns org.candelbio.alzabo.schema-gen
  (:require [org.candelbio.multitool.core :as u]))

;;; Tools for generating schemas


;;; Generate schema from .csv data or other mapseq

;;; Assumes all items have same fields, no nulls, which might not always be the case
(defn- infer-field-def
  [vals]
  (cond
    (number? (first vals)) {:type :number :min (u/min* vals) :max (u/max* vals) }
    (> (count (distinct vals)) 40) {:type :string}
    :else
    {:type :enumerated :values (sort (distinct vals))}
    )
  )

(defn mapseq->schema-fields
  [mapseq]
  (let [keys (keys (first mapseq))]
  (into
   {}
   (for [k keys]
     [k (infer-field-def (map k mapseq))]
     ))))

