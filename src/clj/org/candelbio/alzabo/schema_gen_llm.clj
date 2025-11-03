(ns org.candelbio.alzabo.schema-gen-llm
  (:require [hyperphor.multitool.core :as u]
            [clojure.string :as str]
            [org.candelbio.alzabo.llm :as llm]))


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
