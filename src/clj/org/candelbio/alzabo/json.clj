(ns org.candelbio.alzabo.json
  (:require [org.candelbio.multitool.core :as u]
            [org.candelbio.multitool.cljcore :as ju]
            [org.candelbio.alzabo.config :as config]
            [clojure.data.json :as json]
            [clojure.walk :as w]))

;;; Support for reading json configs (easier to generate from TypeORM and other sources)

(defn read-schema
  [json-file]
  (let [raw (json/read-str (slurp json-file) :key-fn keyword)]
    (update raw :kinds
            (fn [kinds]
              (u/map-values
               (fn [kdef]
                 (update kdef :fields
                         (fn [fields]
                           (u/map-values
                            (fn [fdef]
                              (-> fdef
                                  (update :cardinality keyword)
                                  (update :type (fn [ot]
                                                  ;; TODO use schema/primitives
                                                  (if (contains? #{"String" "Number"} ot)
                                                    (keyword (clojure.string/lower-case ot))
                                                    (keyword ot))))))
                            fields))))
               kinds)))))

(defn write-schema
  []
  (->> "typeorm-schema.json"
       config/realize-path
       read-schema
       #_ enhance-schema
       (ju/schppit (config/realize-path "typeorm-schema.edn"))))
