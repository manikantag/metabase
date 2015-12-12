(ns metabase.models.dependency
  (:require [clojure.set :as set]
            [korma.core :as k]
            [metabase.db :as db]
            (metabase.models [interface :refer :all])
            [metabase.util :as u]))

;;; # IRevisioned Protocl

(defprotocol IDependent
  "Methods an entity may optionally implement to control how dependencies of an instance are captured."
  (dependencies [this id instance]
    "Provide a map of dependent models and their corresponding IDs for the given instance.  Each key in the returned map
     must correspond to a valid Metabase entity model otherwise it will be ignored.  Each value for a given key should
     a vector of integer ids for the given model.

     For example:
         (dependencies Card 13 {})  ->  {:segment [25 134 344]
                                         :table   [18]}"))


;;; # Dependency Entity

(defentity Dependency
  [(k/table :dependency)])


;;; ## Persistence Functions


(defn retrieve-dependencies
  "Get the list of dependencies for a given object."
  [entity id]
  {:pre [(metabase-entity? entity)
         (integer? id)]}
  (db/sel :many Dependency :model (:name entity) :model_id id))

(defn update-dependencies
  "Update the set of `Dependency` objects for a given entity."
  [entity id deps]
  {:pre [(metabase-entity? entity)
         (integer? id)
         (map? deps)]}
  (let [dependency-set   (fn [key]
                           ;; TODO: validate that key is a valid entity model
                           (when (every? integer? (key deps))
                             (for [val (key deps)]
                               {:dependent_on_model (name key), :dependent_on_id val})))
        dependencies-old (set (db/sel :many :fields [Dependency :dependent_on_model :dependent_on_id] :model (:name entity) :model_id id))
        dependencies-new (->> (mapv dependency-set (keys deps))
                              (filter identity)
                              flatten
                              set)
        dependencies+    (set/difference dependencies-new dependencies-old)
        dependencies-    (set/difference dependencies-old dependencies-new)]
    (when (seq dependencies+)
      (let [vs (map #(merge % {:model (:name entity), :model_id id, :created_at (u/new-sql-timestamp)}) dependencies+)]
        (k/insert Dependency (k/values vs))))
    (when (seq dependencies-)
      (doseq [{:keys [dependent_on_model dependent_on_id]} dependencies-]
        ;; batch delete would be nice here, but it's tougher with multiple conditions
        (k/delete Dependency (k/where {:model              (:name entity)
                                       :model_id           id
                                       :dependent_on_model dependent_on_model
                                       :dependent_on_id    dependent_on_id}))))))