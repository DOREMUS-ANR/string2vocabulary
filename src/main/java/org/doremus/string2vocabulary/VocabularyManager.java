package org.doremus.string2vocabulary;

import org.apache.jena.query.*;
import org.apache.jena.rdf.model.*;
import org.apache.jena.rdf.model.impl.StatementImpl;
import org.apache.jena.riot.RDFDataMgr;
import org.apache.jena.riot.Lang;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * The VocabularyManager class
 *
 * @author Pasquale Lisena
 * @since 2017-11-09
 * @see https://github.com/DOREMUS-ANR/string2vocabulary
 */
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
                          " FILTER(isLiteral(?label))} ");
  private static Map<Property, PropMap> prop2FamilyMap;
  private static boolean verbose = false;
  private static String vocabularyDirPath;
  private static StanfordLemmatizer slem;
  private static String lang = "en";

  // === Helper methods =======================================================

  /**
   * String handling approach to finding the extension.
   * This method will check for the dot ‘.' occurrence in the given filename.
   * If it exists, then it will find the last position of the dot ‘.' and return the characters after that, the characters after the last dot ‘.' known as the file extension.
   *
   * Special Cases:
   *   No extension – this method will return an empty String
   *   Only extension – this method will return the String after the dot, e.g. “gitignore”
   * Source: https://www.baeldung.com/java-file-extension
   * @param filename
   * @return String
   */
  public static Optional<String> getExtensionByStringHandling(String filename) {
    return Optional.ofNullable(filename)
      .filter(f -> f.contains("."))
      .map(f -> f.substring(filename.lastIndexOf(".") + 1));
  }

  /**
   * Load the property-vocabulary mapping from a CSV file into a Map object (an object that maps keys to values)
   * @param path Path to the property-vocabulary mapping file
   * @return Map<Property, PropMap>
   * @throws IOException
   */
  private static Map<Property, PropMap> getMapFromCSV(final Path path) throws IOException {
    Model m = ModelFactory.createDefaultModel();

    Stream<String> lines = Files.lines(path);
    Map<Property, PropMap> resultMap = lines.map(PropMap::new)
      .collect(Collectors.toMap(pm -> m.createProperty(pm.getProperty()), pm -> pm));
    lines.close();

    return resultMap;
  }

  /**
   * Helper method for retrieving the value of given parameter
   * @param params A set of key/value parameters
   * @param key The parameter key to read
   * @return String
   */
  private static String getParam(List<String> params, String key) {
    int i = params.indexOf(key);
    if (i < 0) return null;
    return params.get(i + 1);
  }

  // === Class properties setter/getter =======================================

  /**
   * Setter for the logging verbosity
   */
  public static void setVerbose(boolean verbose) {
    VocabularyManager.verbose = verbose;
  }

  /**
   * Setter for the reference vocabulary folder
   */
  public static void setVocabularyFolder(String vocabularyFolder) {
    vocabularyDirPath = vocabularyFolder;
  }

  /**
   * Setter for lemmatiser
   */
  public static void setLang(String _lang) {
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

  // === Processing methods ===================================================

  /**
   * Queries the model for a given property and substitutes objects with relevant vocabulary URI if any.
   */
  private static Model propertyMatching(Model model,
                                        Property property,
                                        String category,
                                        boolean singularise) {
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

      if (verbose) System.out.println("Matched " + count + " elements for " + property.getLocalName());  // TODO: use logging facilities
    } catch (RuntimeException re) {
      System.out.println(re.getMessage());  // TODO: use logging facilities
    }
    return model;
  }

  /**
   * Search for a term in a given family.
   * This performs a normal full search and one in strict mode.
   */
  public static String runSearchInCategory(String vocabularyFolder,
                                           String label,
                                           String singularizationLang,
                                           String lookupLang,
                                           String category) {

    // print full logs
    VocabularyManager.setVerbose(true);

    if (vocabularyFolder == null || label == null || lang == null || category == null) {
      return null;
    } else {

      // set the folder where to find vocabularies
      VocabularyManager.setVocabularyFolder(vocabularyFolder);

      // set the language to be used for singularizing the words
      VocabularyManager.setLang(singularizationLang);

      // Search for a term in a given family
      // this performs a normal full search and one in strict mode
      return VocabularyManager.searchInCategory(label, lookupLang, category, true).toString();
    }
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

  /**
   * Iterate over the properties to map lists and query the model instance (input dataset) for statements.
   */
  public static void string2uri(Model m) {
    prop2FamilyMap.forEach((key, value) -> propertyMatching(
      m,
      key,
      value.getCategory(),
      value.singularise())
    );
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

  // === Run methods ==========================================================

  /**
   * Shortcut to the *init* method with property-vocabulary mapping as a URL
   */
  public static void init(URL property2FamilyResource) throws IOException {
    init(property2FamilyResource.getFile());
  }

  /**
   * Shortcut to the *init* method with property-vocabulary mapping as a file path
   */
  public static void init(String property2FamilyCSV) throws IOException {
    init(getMapFromCSV(Paths.get(property2FamilyCSV)));
  }

  /**
   * VocabularyManager initialisation procedure.
   * Basically,
   * - instanciates fundamental objects
   * - loads vocabularies from resource folder
   */
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

  /**
   * Shortcut to the *run* method with lang set to "en"
   */
  public static void run(String property2family,
                         String vocabularyFolder,
                         Dataset dataset,
                         String namedGraph,
                         Model m,
                         String outputFile) throws IOException {
    run(
      property2family,
      vocabularyFolder,
      dataset,
      namedGraph,
      m,
      outputFile,
      "en"
    );
  }

  /**
   * Full run of the patching process for standalone script
   * @param property2family Table file with property-vocabulary mapping
   * @param vocabularyFolder Folder containing the vocabularies in turtle format
   * @param m The model instance (input dataset)
   * @param outputFile Filename for saving the resulting dataset
   * @param lang Language to be used for singularising the words, e.g. 'en"
   */
  public static void run(String property2family,
                         String vocabularyFolder,
                         Dataset dataset,
                         String namedGraph,
                         Model m,
                         String outputFile,
                         String lang) throws IOException {

    // Vocabulary manager init
    VocabularyManager.setVerbose(true);
    VocabularyManager.setVocabularyFolder(vocabularyFolder);
    VocabularyManager.init(property2family);
    VocabularyManager.setLang(lang);

    // Call processing
    VocabularyManager.string2uri(m);

    // Breaks on no output file config
    if (outputFile == null) return;

    // Save results to file
    // TODO: give the opportunity to save the dataset with patched data VS only the patched model
    System.out.println("Saving data: to '" + outputFile + "' ...");  // TODO: use logging facilities
    FileOutputStream out = new FileOutputStream(outputFile, false);
    // Remark: we assume below that Lang.TRIG encompasses both the Turtle and TriG syntax for serialization
    RDFDataMgr.write(out, dataset.asDatasetGraph(), Lang.TRIG);
    out.close();
    System.out.println("Saving data: to '" + outputFile + "' ... done.");  // TODO: use logging facilities
  }

  /**
   * Program entrypoint
   * Process:
   * - get running arguments
   * - load the data to process into a JENA object, see https://jena.apache.org/documentation/io/rdf-input.html
   * - start the data processing.
   */
  public static void main(String args[]) throws IOException {

    // Get params
    List<String> params = Arrays.asList(args);

    // Load params
    String lang = getParam(params, "--lang");
    String namedGraph = getParam(params, "--graph");  // Example: "http://example.org/graph/object/"
    String property2family = getParam(params, "--map");
    String vocabularyFolder = getParam(params, "--vocabularies");

    // Load params - get input file
    String input = getParam(params, "--input");
    String fileExt = getExtensionByStringHandling(input).orElse("none").toLowerCase();
    if (fileExt.isEmpty()) {
      System.out.println("ERROR: file extension looks empty (should be .ttl or .trig).");  // TODO: use logging facilities
      System.exit(1);
    }

    // Check input file exists
    // This allows to prevent the `org.apache.jena.riot.RiotNotFoundException: Not found` exception at the loadDataset/loadModel statement for cleaner exit when the input file does not exist.
    File f = new File(input);
    if(!f.exists() && !f.isDirectory()) {
      System.out.println("ERROR: file '"+input+"' doesn't exist.");  // TODO: use logging facilities
      System.exit(1);
    }

    // Load params - assert output file name
    String output = getParam(params, "--output");
    if (output == null | output.isEmpty()) output = input.replace(
      "." + fileExt,
      "_output." + fileExt);

    // Log params
    System.out.println(
      "Config:map='" + property2family +
        "':input='" + input +
        "':input(fileExt)='" + fileExt +
        "':vocabularies='" + vocabularyFolder +
        "':output='" + output +
        "':lang='" + lang +
        "'."
    );  // TODO: use logging facilities

    // Load the dataset
    // See https://jena.apache.org/documentation/javadoc/arq/org.apache.jena.arq/org/apache/jena/riot/RDFDataMgr.html
    // Remark: loadDataset() automatically detects the serialization based on the file extension, hence it is useless to call `loadDataset(input, Lang.XXX) if the extension is explicit.
    Dataset dataset = RDFDataMgr.loadDataset(input);

    // Make a model instance (mi) from the dataset and named graph (if relevant)
    Model mi;
    if (namedGraph == null || namedGraph.isEmpty()) {
      // Get the default graph as a Jena Model
      System.out.println("Loading model: from default graph...");  // TODO: use logging facilities
      mi = dataset.getDefaultModel();
    } else {
      // Get a graph by name as a Jena Model
      System.out.println("Loading model: from '" + namedGraph + "' graph...");  // TODO: use logging facilities
      mi = dataset.getNamedModel(namedGraph);
    }

    // Call the patching process
    System.out.println("Processing: start...");  // TODO: use logging facilities
    VocabularyManager.run(
      property2family,
      vocabularyFolder,
      dataset,
      namedGraph,
      mi,
      output,
      lang
    );
    System.out.println("Processing: done.");  // TODO: use logging facilities
    System.exit(0);  // Exit with normal status code.
  }

}

// == EOF =====================================================================
