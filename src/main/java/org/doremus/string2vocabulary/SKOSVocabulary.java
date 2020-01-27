package org.doremus.string2vocabulary;

import org.apache.jena.rdf.model.*;
import org.apache.jena.vocabulary.RDF;
import org.apache.jena.vocabulary.SKOS;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class SKOSVocabulary extends Vocabulary {
  private Map<String, List<Resource>> substitutionMap;
  private Map<String, List<Resource>> substitutionMapNoBrackets;

  public SKOSVocabulary(String name, Model model) {
    super(name, model);

    setSchemePathFromType(SKOS.ConceptScheme);

    // Build a map
    substitutionMap = new HashMap<>();
    substitutionMapNoBrackets = new HashMap<>();

    // for each concept
    StmtIterator conceptIter =
      vocabulary.listStatements(new SimpleSelector(null, RDF.type, SKOS.Concept));

    if (!conceptIter.hasNext()) {
      System.out.println("SKOSVocabulary constructor | Warning: No concepts in the reference rdf at " + name);
      return;
    }

    while (conceptIter.hasNext()) {
      Resource resource = conceptIter.nextStatement().getSubject();
      // get the labels
      StmtIterator labelIterator = resource.listProperties(SKOS.prefLabel);
      //for each label
      while (labelIterator.hasNext()) {
        Literal nx = labelIterator.nextStatement().getLiteral();
        String value = norm(nx.getLexicalForm());
        String valueNb = normNb(nx.getLexicalForm());
        String lang = nx.getLanguage();
        if (lang != null && !lang.isEmpty()){
          value += "@" + nx.getLanguage();
          valueNb += "@" + nx.getLanguage();
        }

        // get the list or create a new one
        List<Resource> ls = substitutionMap.computeIfAbsent(value, k -> new ArrayList<>());
        List<Resource> lsNb = substitutionMapNoBrackets.computeIfAbsent(valueNb, k -> new ArrayList<>());
        // add it to the list
        ls.add(resource);
        lsNb.add(resource);
      }

      labelIterator = resource.listProperties(SKOS.altLabel);
      //for each label
      while (labelIterator.hasNext()) {
        Literal nx = labelIterator.nextStatement().getLiteral();
        String value = norm(nx.getLexicalForm());
        String valueNb = normNb(nx.getLexicalForm());
        String lang = nx.getLanguage();
        if (lang != null && !lang.isEmpty()){
          value += "@" + nx.getLanguage();
          valueNb += "@" + nx.getLanguage();
        }

        // get the list or create a new one
        List<Resource> ls = substitutionMap.computeIfAbsent(value, k -> new ArrayList<>());
        List<Resource> lsNb = substitutionMapNoBrackets.computeIfAbsent(valueNb, k -> new ArrayList<>());
        // add it to the list
        ls.add(resource);
        lsNb.add(resource);
      }
    }
  }


  @Override
  public Resource findConcept(String text, boolean strict, boolean excludeBrackets) {
    String textOnly = text.replaceAll("@[a-z]{2,3}$", "");

    Map<String, List<Resource>> map = excludeBrackets ? substitutionMapNoBrackets : substitutionMap;
    for (Map.Entry<String, List<Resource>> entry : map.entrySet()) {
      String key = entry.getKey();
      String keyPlain = key.replaceAll("@[a-z]{2,3}$", "");

      boolean textLangMatch = text.equalsIgnoreCase(key);
      boolean textOnlyMatch = !strict && textOnly.equalsIgnoreCase(keyPlain);

      if (textLangMatch || textOnlyMatch) {
        List<Resource> matches = entry.getValue();
        Resource bestMatch = null;
        for (Resource m : matches) {
          if (bestMatch == null) {
            bestMatch = m;
            continue;
          }

          // if I already had a "bestMatch"
          // choose the most specific one (skos:narrower)
          Statement narrower = bestMatch.getProperty(SKOS.narrower);
          if (narrower != null) bestMatch = m;
        }

        return bestMatch;
      }
    }
    return null;
  }

}
