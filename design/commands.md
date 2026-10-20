# Needs a better command parsing feature
One that can generate doc automatically

See Rawsugar but maybe a good idea to find Clojure Library

# Get rid of separate config
Makes no sense, put it in schema (at runtime if necessary)


# Commands

## documentation

## add-icons
## add-categories
These are primarily so you can update old schemas
Hm, or could be part of an import -> export pipeline? So Maybe are logically options to import

## server
Is this needed any mroe? It's all static files
Of course could be useful in the future

## datomic
I think this does a datomic import? Could do it on arbitrary DBs, but not very useful without CANDEL metamodel

In any case should be import datomic

## import
## Export
Coud make doc just an export format, would make things flexible
## Transform
An import + an export, concatenated

Logically these is the most important thing, but have no commands for it. Weird.


### SQL DDL
### Datomic ?
### RDFS TTL
### LinkML ???
### GraphQL ????
### JSON-LD
### etc.

# generate-data
generates sample data for a schema using LLMs

Really should be hooked up to ontogeny, or moved there (didn't I do that with schema gen?
