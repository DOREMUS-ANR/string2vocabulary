package org.doremus.string2vocabulary;

import org.apache.jena.query.*;
import org.apache.jena.rdf.model.*;
import org.apache.jena.rdf.model.impl.StatementImpl;
import org.apache.jena.riot.RDFDataMgr;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class VocabularyManager {
  private static List<Vocabulary> vocabularies;
  private static Map<String, List<Vocabulary>> vocabularyMap;
  private static final ParameterizedSparqlString propertyMatchingSPARQL =
          new ParameterizedSparqlString(
                  "prefix rdfs: <http://www.w3.org/2000/01/rdf-schema#label>\n" +
                          "prefix ecrm: <http://erlangen-crm.org/current/>\n" +
                          "select DISTINCT ?s ?o ?label where {\n" +
                          " { ?s ?p ?o . ?o rdfs:label | ecrm:P1_is_identified_by ?label }\n" +
                          " UNION\n" +
                          " { ?s ?p ?label }\n" +
                          " FILTER(isLiteral(?label))}");
  private static Map<Property, String> prop2FamilyMap;
  private static boolean verbose = false;
  private static String vocabularyDirPath;

  public static void init(URL property2FamilyResource) throws IOException {
    init(property2FamilyResource.getFile());
  }

  public static void init(String property2FamilyCSV) throws IOException {
    init(getMapFromCSV(Paths.get(property2FamilyCSV)));
  }

  public static void init(Map<Property, String> property2FamilyMap) throws IOException {
    vocabularies = new ArrayList<>();
    vocabularyMap = new HashMap<>();

    prop2FamilyMap = property2FamilyMap;

    // load vocabularies from resource folder
    File vocabularyDir = new File(vocabularyDirPath);

    File[] files = vocabularyDir.listFiles((dir, name) -> name.toLowerCase().endsWith(".ttl"));
    assert files != null;
    for (File file : files) {
      Vocabulary vocabulary = Vocabulary.fromFile(file);
      if (vocabulary == null) continue;
      vocabularies.add(vocabulary);

      List<Vocabulary> thisCategory = vocabularyMap.computeIfAbsent(vocabulary.getCategory(), k -> new ArrayList<>());
      thisCategory.add(vocabulary);
      Collections.sort(thisCategory);
    }

    Collections.sort(vocabularies);
  }

  public static Map<Property, String> getMapFromCSV(final Path path) throws IOException {
    Model m = ModelFactory.createDefaultModel();

    Stream<String> lines = Files.lines(path);
    Map<Property, String> resultMap =
            lines.map(line -> line.split(","))
                    .collect(Collectors.toMap(line -> m.createProperty(line[0]), line -> line[1]));

    lines.close();

    return resultMap;
  }


  public static Vocabulary getVocabulary(String name) {
    for (Vocabulary v : vocabularies)
      if (name.equals(v.getName())) return v;

    return null;
  }

  public static MODS getMODS(String name) {
    Vocabulary v = getVocabulary(name);
    if (v != null && v instanceof MODS) return (MODS) v;
    return null;
  }

  private static List<Vocabulary> getVocabularyCategory(String category) {
    return vocabularyMap.get(category);
  }

  public static void string2uri(Model m) {
    for (Map.Entry<Property, String> entry : prop2FamilyMap.entrySet())
      propertyMatching(m, entry.getKey(), entry.getValue());
  }


  private static Model propertyMatching(Model model, Property property, String category) {
    int count = 0;
    List<Statement> statementsToRemove = new ArrayList<>(),
            statementsToAdd = new ArrayList<>();

    propertyMatchingSPARQL.setParam("?p", property);
    QueryExecution qexec = QueryExecutionFactory.create(propertyMatchingSPARQL.asQuery(), model);
    ResultSet result = qexec.execSelect();

    while (result.hasNext()) {
      QuerySolution res = result.next();
      Literal label = res.get("label").asLiteral();

      Resource concept = searchInCategory(label.toString(), null, category);
      if (concept == null) continue; //match not found

      Resource subject = res.get("s").asResource();
      if (res.get("o") != null) {
        Resource object = res.get("o").asResource();
        // remove all properties of the object
        for (StmtIterator it = object.listProperties(); it.hasNext(); )
          statementsToRemove.add(it.nextStatement());
        // remove the link between the object and the subject
        statementsToRemove.add(new StatementImpl(subject, property, object));
      } else
        statementsToRemove.add(new StatementImpl(subject, property, label));

      count++;
      statementsToAdd.add(new StatementImpl(subject, property, concept));
    }

    model.remove(statementsToRemove);
    model.add(statementsToAdd);

    if (verbose) System.out.println("Matched " + count + " elements for " + property.getLocalName());
    return model;
  }


  public static Resource searchInCategory(String label, String lang, String category) {
    String langLabel;
    if (lang == null) {
      langLabel = label;
      String[] temp = langLabel.split("@");
      label = temp[0];
      lang = temp.length > 1 ? temp[1] : null;
    } else {
      langLabel = label + "@" + lang;
    }
    List<Vocabulary> vList = getVocabularyCategory(category);
    Resource concept;
    // first check: text + language
    for (Vocabulary v : vList) {
      concept = v.findConcept(langLabel, true);
      if (concept != null) return concept;
    }
    // second check: text without caring about the language
    for (Vocabulary v : vList) {
      concept = v.findConcept(label, false);
      if (concept != null) return concept;
    }

    // workaround: mi bemol => mi bemol majeur
    if ("key".equals(category) && !label.endsWith("majeur")) {
      return searchInCategory(label + " majeur", lang, category);
    }
    return null;
  }


  public static void run(String property2family, String vocabularyFolder, Model m, String outputFile) throws IOException {
    // full run for standalone script
    VocabularyManager.setVerbose(true);
    VocabularyManager.setVocabularyFolder(vocabularyFolder);
    VocabularyManager.init(property2family);
    VocabularyManager.string2uri(m);


    if (outputFile == null) return;

    FileWriter out = new FileWriter(outputFile);
    m.write(out, "TURTLE");

    out.close();
  }

  public static void setVerbose(boolean verbose) {
    VocabularyManager.verbose = verbose;
  }

  public static void setVocabularyFolder(String vocabularyFolder) {
    vocabularyDirPath = vocabularyFolder;
  }

  public static void main(String args[]) throws IOException {
    List<String> params = Arrays.asList(args);

    String property2family = getParam(params, "--map");
    String input = getParam(params, "--input");
    String vocabularyFolder = getParam(params, "--vocabularies");
    String output = getParam(params, "--output");
    if(output == null) output = input.replace(".ttl", "_output.ttl");

    Model mi = RDFDataMgr.loadModel(input);
    VocabularyManager.run(property2family, vocabularyFolder, mi, output);

  }

  private static String getParam(List<String> params, String key) {
    int i = params.indexOf(key);
    if (i < 0) return null;
    return params.get(i+1);
  }


}
