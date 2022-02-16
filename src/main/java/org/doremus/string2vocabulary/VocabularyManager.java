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
                  "prefix rdfs: <http://www.w3.org/2000/01/rdf-schema#>\n" +
                          "prefix ecrm: <http://erlangen-crm.org/current/>\n" +
                          "select DISTINCT ?s ?o ?label where {\n" +
                          " { ?s ?p ?o . ?o rdfs:label | ecrm:P1_is_identified_by ?label }\n" +
                          " UNION\n" +
                          " { ?s ?p ?label }\n" +
                          " FILTER(isLiteral(?label))}");
  private static Map<Property, PropMap> prop2FamilyMap;
  private static boolean verbose = false;
  private static String vocabularyDirPath;
  private static StanfordLemmatizer slem;
  private static String lang = "en";

  public static void init(URL property2FamilyResource) throws IOException {
    init(property2FamilyResource.getFile());
  }

  public static void init(String property2FamilyCSV) throws IOException {
    init(getMapFromCSV(Paths.get(property2FamilyCSV)));
  }

  public static void init(Map<Property, PropMap> property2FamilyMap) {
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

    setLang(lang);
  }

  private static Map<Property, PropMap> getMapFromCSV(final Path path) throws IOException {
    Model m = ModelFactory.createDefaultModel();

    Stream<String> lines = Files.lines(path);
    Map<Property, PropMap> resultMap = lines.map(PropMap::new)
            .collect(Collectors.toMap(pm -> m.createProperty(pm.getProperty()), pm -> pm));
    lines.close();

    return resultMap;
  }

  public static void setLang(String _lang) {
    // for lemmatiser
    lang = _lang;
    slem = new StanfordLemmatizer(lang);
  }

  public static Vocabulary getVocabulary(String name) {
    return vocabularies.stream()
            .filter(v -> name.equals(v.getName()))
            .findFirst().orElse(null);
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
    prop2FamilyMap.forEach((key, value) -> propertyMatching(m, key, value.getCategory(), value.singularise()));
  }


  private static Model propertyMatching(Model model, Property property, String category, boolean singularise) {
    int count = 0;
    List<Statement> statementsToRemove = new ArrayList<>(),
            statementsToAdd = new ArrayList<>();
    try {
      propertyMatchingSPARQL.setParam("?p", property);
      QueryExecution qexec = QueryExecutionFactory.create(propertyMatchingSPARQL.asQuery(), model);
      ResultSet result = qexec.execSelect();

      while (result.hasNext()) {
        QuerySolution res = result.next();
        Literal label = res.get("label").asLiteral();

        Resource concept = searchInCategory(label.toString(), null, category, singularise);
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
    } catch (RuntimeException re) {
      System.out.println(re.getMessage());
    }
    return model;
  }


  public static Resource searchInCategory(String label, String lang, String category, boolean singularise) throws RuntimeException {
    try {
      List<Vocabulary> vList = getVocabularyCategory(category);
      return searchInCategory(label, lang, vList, singularise);
    } catch (NullPointerException npe) {
      throw new RuntimeException("Family of vocabularies not available: " + category);
    }
  }

  public static Resource searchInCategory(String label, String lang, List<Vocabulary> category, boolean singularise) {
    label = Vocabulary.norm(label);

    String langLabel;
    if (lang == null) {
      langLabel = label;
      String[] temp = langLabel.split("@");
      label = temp[0];
      lang = temp.length > 1 ? temp[1] : null;
    } else {
      langLabel = label + "@" + lang;
    }

    if (singularise) {
      // first check: singularise just the first word
      Resource match = searchInCategory(toSingular(label, false), lang, category, false);
      if (match != null) return match;
      // second check: singularise the whole string
      match = searchInCategory(toSingular(label, true), lang, category, false);
      if (match != null) return match;
    }

    Resource concept;
    // first check: text + language
    for (Vocabulary v : category) {
      concept = v.findConcept(langLabel, true);
      if (concept != null) return concept;
    }
    // second check: text without caring about the language
    for (Vocabulary v : category) {
      concept = v.findConcept(label, false);
      if (concept != null) return concept;
    }
    // third check: exclude brackets
    for (Vocabulary v : category) {
      concept = v.findConcept(langLabel, true, true);
      if (concept != null) return concept;
    }
    // fourth check: exclude brackets + not caring about the language
    for (Vocabulary v : category) {
      concept = v.findConcept(langLabel, false, true);
      if (concept != null) return concept;
    }

    // workaround: mi bemol => mi bemol majeur
    if ("key".equals(category) && !label.endsWith("majeur")) {
      return searchInCategory(label + " majeur", lang, category, singularise);
    }
    return null;
  }

  private static String toSingular(String r, boolean full) {
    if (r == null || r.isEmpty()) return "";
    if (full)
      return slem.lemmatize(r).stream()
              .collect(Collectors.joining(" "));


    String[] parts = r.split(" ");
    if (parts.length == 1) return slem.lemmatize(parts[0]).get(0);

    // cornets à pistons --> cornet à pistons
    parts[0] = slem.lemmatize(parts[0]).get(0);
    return String.join(" ", parts);
  }


  public static void run(String property2family, String vocabularyFolder, Model m, String outputFile) throws IOException {
    run(property2family, vocabularyFolder, m, outputFile, "en");
  }

  public static void run(String property2family, String vocabularyFolder, Model m, String outputFile, String lang) throws IOException {
    // full run for standalone script
    VocabularyManager.setVerbose(true);
    VocabularyManager.setVocabularyFolder(vocabularyFolder);
    VocabularyManager.init(property2family);
    VocabularyManager.setLang(lang);
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
    if (output == null) output = input.replace(".ttl", "_output.ttl");
    String lang = getParam(params, "--lang");
    System.out.println("Config:map='" + property2family + "':input='" + input + "':vocabularies='" + vocabularyFolder + "':output='" + output + "':lang='" + lang + "'.");


    Model mi = RDFDataMgr.loadModel(input);
    VocabularyManager.run(property2family, vocabularyFolder, mi, output, lang);

  }

  private static String getParam(List<String> params, String key) {
    int i = params.indexOf(key);
    if (i < 0) return null;
    return params.get(i + 1);
  }

}
