package org.doremus.string2vocabulary;

public class PropMap {
  private final String property;
  private final String category;
  private final boolean singularise;

  public PropMap(String line) {
    this(line.split(","));
  }

  public PropMap(String[] line) {
    property = line[0];
    category = line[1];
    singularise = line.length > 2 && ("true".equals(line[2]) || "singular".equals(line[2]));
  }

  public String getProperty() {
    return property;
  }

  public String getCategory() {
    return category;
  }

  public boolean singularise() {
    return singularise;
  }
}
