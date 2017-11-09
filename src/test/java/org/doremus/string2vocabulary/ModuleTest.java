package org.doremus.string2vocabulary;

import org.apache.jena.rdf.model.Model;
import org.apache.jena.riot.RDFDataMgr;
import org.junit.Assert;
import org.junit.Test;

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
      Model mi = RDFDataMgr.loadModel(input);
      Model mo = RDFDataMgr.loadModel(output);
      VocabularyManager.run(property2family, vocabularyFolder, mi, "test.ttl");
      VocabularyManager.run(property2family, vocabularyFolder, mo, null);

      Assert.assertEquals(toTtlString(mi), toTtlString(mo));
    } catch (IOException e) {
      e.printStackTrace();
    }
  }

  private String toTtlString(Model m) {
    StringWriter sw = new StringWriter();
    m.write(sw, syntax);
    return sw.toString();
  }


}