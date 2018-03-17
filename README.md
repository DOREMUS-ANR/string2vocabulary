String2Vocabulary
=================

[![](https://jitpack.io/v/DOREMUS-ANR/string2vocabulary.svg)](https://jitpack.io/#DOREMUS-ANR/string2vocabulary)


Look for literal in the RDF and substitute with URIs from controlled vocabularies.
Built with Gradle and Apache Jena.

It uses the name of the vocabularies files for grouping them in **families**. I.e. `city-italy.ttl` and `city-france.ttl` are part of the family `city`.

## Input

The library needs in input:
- a **folder** containing vocabularies
- for a full graph replacement, a **configuration in csv** that declares the property to match, the relative vocabulary family, an if it should eventually check for the singular version of the label. Example:

```csv
http://data.doremus.org/ontology#U2_foresees_use_of_medium_of_performance,mop,singular
http://data.doremus.org/ontology#U11_has_key,key,
```

Given as input:
```turtle
ns:myMusicWork mus:U11_has_key [
          a mus:M4_Key ;
          rdfs:label "Ré majeur"@fr ] ;
   mus:U2_foresees_use_of_medium_of_performance "mezzosoprano" .
```

this produce as output.

```turtle
ns:myMusicWork mus:U11_has_key <http://data.doremus.org/vocabulary/key/d> ;
   mus:U2_foresees_use_of_medium_of_performance  <http://data.doremus.org/vocabulary/iaml/mop/vms> .
```

## Features

- 2 vocabulary syntax supported: SKOS and MODS
- Support for families of vocabularies
- Replace literals that match the given label
- Replace objects that have a `rdfs:label` or `ecrm:P1_is_identified_by` which match the given label
- _Strict mode_: match both label and language
- Normalise the labels by removing punctuation, decoding to ASCII, using lowercase
- Search also for the singular version of the word with [Stanford CoreNLP](https://github.com/stanfordnlp/CoreNLP)

## As a module

1. Add it as dependency. E.g. in `build.gradle`:

  ```
  dependencies {
     compile 'com.github.DOREMUS-ANR:string2vocabulary:0.1'
  }
  ```

2. Import and init in your Java class

  ```java
  import org.doremus.string2vocabulary.VocabularyManager;

  // ...

  // print full logs
  VocabularyManager.setVerbose(true);

  // set the folder where to find vocabuaries
  VocabularyManager.setVocabularyFolder("/location/to/vocabularyFolder");
  // set the folder where to find the config csv
  VocabularyManager.init("/location/to/property2family.csv");
  // set the language to be used for singularising the words
  VocabularyManager.setLang("fr");
  ```
3. Use it :)

```java
// Search for a term in a given family
// this performs a normal full search and one in strict mode
VocabularyManager.searchInCategory("violin", "en", "mop");
// --> http://www.mimo-db.eu/InstrumentsKeywords/3573

// or
// Search for a term in a given vocabulary
VocabularyManager.getVocabulary("mop-iaml").findConcept("violin", false);
// --> http://data.doremus.org/vocabulary/iaml/mop/svl
// strict mode
VocabularyManager.getVocabulary("mop-iaml").findConcept("violin@it", true);
// --> null

// or
// Get the URI by code (what is written after the namespace)
VocabularyManager.getVocabulary("key").getConcept("dm");
// --> http://data.doremus.org/vocabulary/key/dm


// or
// Full graph replacement
// search and substitute in the whole Jena Model
// (following the csv configuration)
VocabularyManager.string2uri(model)

```

See the [test](src/test) folder for another example of usage.

## Command Line

    gradle run -Pmap="/location/to/property2family.csv"  -Pinput="/location/to/input.ttl"
    -Pvocabularies="/location/to/vocabularyFolder"


| param | example | comment |
| ----- | ------- | ------- |
| map   | `/location/to/property2family.csv` | A table with mapping property-vocabulary |
| vocabularies   | `/location/to/vocabularyFolder` | Folder containing the vocabularies in turtle format |
| input   | `/location/to/input.ttl` | The input turtle file |
| output _(Optional)_   | `/location/to/output.ttl` | The output turtle file. Default: `inputPath/inputName_output.ttl` |
| lang  _(Optional)_  | `fr` | Language to be used for singularising the words. Default: `en`. |