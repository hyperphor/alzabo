(ns hyperphor.alzabo.schema-gen-llm
  (:require [hyperphor.multitool.core :as u]
            [clojure.string :as str]
            [hyperphor.alzabo.llm :as llm]))


(def system-prompt
  "You are a knowledge representation expert who knows how to create clean and elegant ontologies and schemas for various domains")

(def sample-schema "/opt/mt/repos/hyperphor/alzabo/resources/jazz-schema.edn")

(defn sgen
  [domain]
  (let [query (format "Create an Alzabo schema for the %s domain, using the example as a guide. Include classes, attributes, and relations. For each attribuate and relation, include a type and a documentation string" domain)]
    (-> {:model "gpt-4.1"
         :messages [{:role "system" :content system-prompt}
                    {:role "user" :content query}
                    {:role "user" :content (str "example: " (slurp sample-schema))}
                    ]}
        llm/run-chat-completion
        (get-in [:choices 0 :message :content])
        llm/extract-clojure
        first
        )))

;;; Adds documentation strings to CANDEL, which is a bit lacking in that regard
(defn add-doc
  [domain schema]
  (let [query (format "Given this Alzabo schema for the %s domain, add documentation to each kind, attribute, and enum value if id doesn't already exist" domain)]
    (-> {:model "gpt-4.1"
         :messages [{:role "system" :content system-prompt}
                    {:role "user" :content query}
                    {:role "user" :content (str "schema: " (print-str schema))}
                    ]}
        llm/run-chat-completion
        (get-in [:choices 0 :message :content])
        ;; Produces incorrect edn with ellipses, so extracted and edited by hand
        #_ llm/extract-clojure
        #_ first
        )))


(comment
  (def schema (hyperphor.alzabo.import.candel/produce-schema))
  (def doc (add-doc "cancer immunotherapy research" schema))
  ;; Some hand tweaking
  (def doc (read-string (slurp "resources/candel/llm-doc.edn")))

  (hyperphor.alzabo.output/write-schema
   (u/merge-recursive schema doc)
   "resources/public/schema/candel/schemax.edn")

  (hyperphor.alzabo.core/demo  "resources/public/schema/candel/schemax.edn" "candelx")
  )
