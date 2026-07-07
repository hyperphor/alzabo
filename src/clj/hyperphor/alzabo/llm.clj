(ns hyperphor.alzabo.llm
  (:require [hato.client :as client]
            [clojure.data.json :as json]
            [environ.core :as env]
            [clojure.string :as str]
            [hyperphor.multitool.core :as u]
            ))

;;; TODO flush this and use ellellem

;;; TODO this should be changeable, maybe through config
(def system-prompt
  "You are a knowledge representation expert who knows how to create clean and elegant ontologies and schemas for various domains")

(defn api-key
  []
  (env/env :openai-api-key))

(defn api-get
  [url params]
  (:body
   (client/get (str "https://api.openai.com/v1" url)
              {:query-params params
               :headers {"Authorization" (str "Bearer " (api-key))
                         "OpenAI-Beta" "assistants=v2"}
               :as :json
               })))

(defn list-models
  []
  (api-get "/models" {}))

(defn api-post
  [url q]
  (:body
   (client/post (str "https://api.openai.com/v1" url)
                {:as :json
                 :headers {"Authorization" (str "Bearer " (api-key))}
                 :content-type :application/json
                 :body (json/write-str q)}
                )))

(defn run-chat-completion
  [q]
  (api-post "/chat/completions" q))

(defn text-query
  [text]
  (-> {:model "gpt-4"
       :messages [{:role "system" :content system-prompt}
                  {:role "user" :content text}
                  ]}
      run-chat-completion
      (get-in [:choices 0 :message :content])
      ))

(defn read-json-safe
  [s]
  (try
    (json/read-str s :key-fn keyword)
    (catch Throwable e
      s)))

;;; Extract code from a llm response (md).
;;; Assumes a type, assumes only one code block
;;; Returns [type code non-code-text]
(defn extract-code
  [s]
  (let [[m? type code] (re-find #"```(.*)\n([\s\S]*?)```" s)]
    (when m?
      [(keyword type) code (str/replace s m? "")])))

;;; TODO sometimes it returens separate ```json elements! Argh
(defn extract-json
  [s]
  (let [[type code text] (extract-code s)]
    (if (= type :json)
      [(read-json-safe code) text]
      ;; Or, maybe encoded differently
      (let [xtract (or (re-find #"JSON[\s\S]*?(\[[\s\S]*\])" s)
                       (re-find  #"(?s)[\s\S]*?(\[\s*\{[\s\S]*\}\s*\])" s))] ;finds [{ ... }]
        (if xtract
          [(read-json-safe (second xtract)) s]
          nil)))))

(defn extract-clojure
  [s]
  (try                                  ;TODO pull out into macro → way
    (let [[type code text] (extract-code s)]
      (if (= type :clojure)               ;or :edn
        [(read-string code) text]         ;TODO safety
        ))
    (catch Exception e
      (throw (ex-info "Clojure extract failure" {:s s})))))  

(comment
  (extract-clojure "```clojure
{:a 1}
```")
  )

(defn extract-edn
  [s]
  (or (extract-clojure s)
      (try                                  ;TODO pull out into macro → way
        (read-string s)
        (catch Exception e
          (throw (ex-info "EDN extract failure" {:s s})))))  
  )


(defn json-query
  [str]
  (-> str
      text-query
      (extract-json)))
