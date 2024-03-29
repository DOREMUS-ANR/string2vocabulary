package org.doremus.string2vocabulary;

import org.apache.jena.query.QueryExecution;
import org.apache.jena.query.QueryExecutionFactory;
import org.apache.jena.query.ResultSet;
import org.apache.jena.rdf.model.*;
import org.apache.jena.vocabulary.DCTerms;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public class MODS extends Vocabulary {
  // MODS class/properties shortcut
  private static final Model m = ModelFactory.createDefaultModel();
  public static final String uri = "http://www.loc.gov/standards/mods/rdf/v1/#";
  public static final Resource ModsResource = m.createResource(uri + "ModsResource");


  public MODS(String name, Model model) {
    super(name, model);

    setSchemePathFromType("http://www.w3.org/ns/dcat#Catalog");
  }

  @Override
  public Resource findConcept(String text, boolean strict, boolean excludeBrackets) {
    return findModsResource(text, null);
  }

  public Resource findModsResource(String identifier, List<String> subjects) {
    if (identifier == null || identifier.isEmpty()) return null;

    String modsSearch =
      "prefix modsrdf: <http://www.loc.gov/standards/mods/rdf/v1/#>\n" +
        "select distinct ?cat where {\n" +
        "  { ?cat modsrdf:identifier ?id}\n" +
        "  UNION {\n" +
        "    ?cat modsrdf:identifierGroup / modsrdf:identifierGroupValue ?id\n" +
        "  }\n" +
        "  FILTER (lcase(str(?id)) = \"" + identifier.toLowerCase() + "\")\n" +
        "}";

    // search all catalogs with that identifier
    QueryExecution qexec = QueryExecutionFactory.create(modsSearch, vocabulary);
    ResultSet result = qexec.execSelect();
    List<Resource> candidateCatalogs = new ArrayList<>();

    // add them to the canditates list
    while (result.hasNext()) {
      Resource resource = result.next().get("cat").asResource();
      candidateCatalogs.add(resource);
    }

    if (candidateCatalogs.size() == 0)
      return null;

    if (candidateCatalogs.size() > 1) {
      if (subjects != null) {
        // load related artists
        for (Resource res : candidateCatalogs) {
          Statement curSubjectStatements = res.getProperty(DCTerms.subject);
          if (curSubjectStatements == null) return null;
          String curSubject = curSubjectStatements.getObject().toString();
          for (String s : subjects) {
            if (Objects.equals(s, curSubject))
              return res;
          }
        }
        // System.out.println("Too many results for catalog " + identifier + " and composers " + composers + ". It will be not linked.");
      }
      return null;
    }

    return candidateCatalogs.get(0);
  }

  public List<Resource> bySubject(String subject) {
    if (subject == null) return null;
    List<Resource> candidateCatalogs = new ArrayList<>();

    // load related artists
    ResIterator it = vocabulary.listResourcesWithProperty(DCTerms.subject, vocabulary.getResource(subject));
    while (it.hasNext()) {
      Resource res = it.next();
      candidateCatalogs.add(res);
    }
    return candidateCatalogs;
  }
}
