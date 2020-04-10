package org.doremus.string2vocabulary;

import org.apache.jena.rdf.model.*;
import org.apache.jena.vocabulary.RDF;
import org.apache.jena.vocabulary.SKOS;

import java.util.*;

public class SKOSVocabulary extends Vocabulary {
  private final Map<String, Resource> substitutionMap;
  private final Map<String, Resource> substitutionMapNoBrackets;
  private final Map<String, Resource> substitutionMapPlain;
  private final Map<String, Resource> substitutionMapPlainNoBrackets;

  public SKOSVocabulary(String name, Model model) {
    super(name, model);

    setSchemePathFromType(SKOS.ConceptScheme);

    // Build maps
    substitutionMap = new HashMap<>();
    substitutionMapNoBrackets = new HashMap<>();
    substitutionMapPlain = new HashMap<>();
    substitutionMapPlainNoBrackets = new HashMap<>();

    // for each concept
    StmtIterator conceptIter =
      vocabulary.listStatements(new SimpleSelector(null, RDF.type, SKOS.Concept));

    if (!conceptIter.hasNext()) {
      System.out.println("SKOSVocabulary constructor | Warning: No concepts in the reference rdf at " + name);
      return;
    }

    while (conceptIter.hasNext())
      processConcept(conceptIter.nextStatement().getSubject());
  }

  private void processConcept(Resource resource) {
    // get the labels
    StmtIterator labelIterator = resource.listProperties(SKOS.prefLabel);
    //for each label
    while (labelIterator.hasNext()) {
      Literal nx = labelIterator.nextStatement().getLiteral();
      String value = norm(nx.getLexicalForm());
      String valueNb = normNb(nx.getLexicalForm());

      // add the value or - if already there - keep the best one
      putKeepBest(value, resource, substitutionMapPlain);
      putKeepBest(valueNb, resource, substitutionMapPlainNoBrackets);

      // now, manage the lang tag
      String lang = nx.getLanguage();
      if (lang != null && !lang.isEmpty()) {
        value += "@" + nx.getLanguage();
        valueNb += "@" + nx.getLanguage();
      }

      // add the value or - if already there - keep the best one
      putKeepBest(value, resource, substitutionMap);
      putKeepBest(valueNb, resource, substitutionMapNoBrackets);
    }

    labelIterator = resource.listProperties(SKOS.altLabel);
    //for each label
    while (labelIterator.hasNext()) {
      Literal nx = labelIterator.nextStatement().getLiteral();
      String value = norm(nx.getLexicalForm());
      String valueNb = normNb(nx.getLexicalForm());
      // add the value or - if already there, skip
      putOrSkip(value, resource, substitutionMapPlain);
      putOrSkip(valueNb, resource, substitutionMapPlainNoBrackets);

      String lang = nx.getLanguage();
      if (lang != null && !lang.isEmpty()) {
        value += "@" + nx.getLanguage();
        valueNb += "@" + nx.getLanguage();
      }

      // add the value or - if already there, skip
      putOrSkip(value, resource, substitutionMap);
      putOrSkip(valueNb, resource, substitutionMapNoBrackets);
    }
  }

  private void putOrSkip(String value, Resource resource, Map<String, Resource> map) {
    if (!map.containsKey(value))
      map.put(value, resource);
  }

  private void putKeepBest(String value, Resource resource, Map<String, Resource> map) {
    if (map.containsKey(value)) {
      Resource oldRes = map.get(value);
      Statement narrower = oldRes.getProperty(SKOS.narrower);
      if (narrower != null) map.put(value, resource);
    } else map.put(value, resource);
  }


  @Override
  public Resource findConcept(String text, boolean strict, boolean excludeBrackets) {
    // remove the lang tag if not strict
    text = strict ? text : text.replaceAll("@[a-z]{2,3}$", "");

    // select the right substitution map
    Map<String, Resource> map;
    if (strict)
      map = excludeBrackets ? substitutionMapNoBrackets : substitutionMap;
    else
      map = excludeBrackets ? substitutionMapPlainNoBrackets : substitutionMapPlain;

    return map.get(text.toLowerCase());
  }

}
