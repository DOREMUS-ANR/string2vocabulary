String2Vocabulary
=================

Look for literal in the RDF and substitute with URIs from controlled vocabularies.
Built with Gradle and Apache Jena.

## As a module

See the [test](src/test) folder for an example of usage.

## Command Line

    gradle run -Pmap="/location/to/property2family.csv"  -Pinput="/location/to/input.ttl" 
    -Pvocabularies="/location/to/vocabularyFolder"

| param | example | comment |
| ----- | ------- | ------- |
| map   | "/location/to/property2family.csv" | A table with mapping property-vocabulary |
| input   | "/location/to/input.ttl" | The input turtle file |
| output _(Optional)_   | "/location/to/output.ttl" | The output turtle file. Default: `inputPath/inputName_output.ttl` |
| vocabularies   | "/location/to/vocabularyFolder" | Folder containing the vocabularies in turtle format |