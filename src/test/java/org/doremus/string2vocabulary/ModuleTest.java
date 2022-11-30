package org.doremus.string2vocabulary;

import org.apache.jena.query.Dataset;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.riot.RDFDataMgr;
import org.junit.Assert;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.io.StringWriter;

public class ModuleTest {
  private static final String syntax = "TURTLE";

  @Test
  public void string2uri() {
    ClassLoader classLoader = getClass().getClassLoader();
    String property2family = classLoader.getResource("property2family.csv").getFile();
    String input = classLoader.getResource("input.ttl").getFile();
    String output = classLoader.getResource("output.ttl").getFile();
    String vocabularyFolder = classLoader.getResource("vocabulary").getPath();

    try {
      String ng = "";
      Dataset din = RDFDataMgr.loadDataset(input);
      Dataset dout = RDFDataMgr.loadDataset(output);
      Model mi = din.getDefaultModel();
      Model mo = dout.getDefaultModel();
      VocabularyManager.run(property2family, vocabularyFolder, din,  ng, mi, null, "fr");
      VocabularyManager.run(property2family, vocabularyFolder, dout, ng, mo, null, "fr");

      Assert.assertEquals(toTtlString(mi), toTtlString(mo));
    } catch (IOException e) {
      e.printStackTrace();
    }
  }

  @Test
  public void matchNoBrackets() {
    ClassLoader classLoader = getClass().getClassLoader();
    String vocabularyFolder = classLoader.getResource("vocabulary").getPath();


    Vocabulary v = Vocabulary.fromFile(new File(vocabularyFolder + "/test.ttl"));

    Resource brackMatch = v.findConcept("test@en", true);
    Resource noBrackMatch = v.findConcept("test@en", true, true);
    System.out.println(noBrackMatch);
    Assert.assertNull(brackMatch);
    Assert.assertNotNull(noBrackMatch);
  }

  private String toTtlString(Model m) {
    StringWriter sw = new StringWriter();
    m.write(sw, syntax);
    return sw.toString();
  }


}
