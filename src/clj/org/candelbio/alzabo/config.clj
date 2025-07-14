(ns org.candelbio.alzabo.config
  (:require [clojure.edn :as edn]
            [me.raynes.fs :as fs]
            [org.candelbio.multitool.core :as u])
  )

(def the-config (atom nil))
(def config-path (atom nil))

(defn set-config!
  [config]                              ;filename or map
  (let [config (if (string? config)
                 (do
                   (reset! config-path config)
                   (edn/read-string (slurp config)))
                 config)]
    (reset! the-config config)))

(defn set!
  [att value]
  (swap! the-config assoc att value))

(defn config
  [& keys]
  (assert @the-config "Config not set")
  (get-in @the-config keys))



;;; TODO document or default config vars here

(defn output-path
  [filename]
  (str
   ;; This rigamarole lets you use relative paths and fs/with-cwd
   (fs/file
    (str (u/expand-template (config :output-path) (config))
         filename))))

;; TODO → multitool (with some cleanup)
(defn realize-rel-path
  [base path]
  (str (.getParentFile (fs/file base))
       "/"
       path))

(defn realize-path
  [path]
  (realize-rel-path @config-path path))

;;; This bit of hackery seems to come and go
;; TODO → multitool (with some cleanup)
(defn realize-rel-path
  [base path]
  (str (.getParentFile (fs/file base))
       "/"
       path))

(defn realize-path
  [path]
  (realize-rel-path @config-path path))

