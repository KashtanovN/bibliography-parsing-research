package ru.ssau.bibliography.research;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.regex.*;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.*;
import org.xml.sax.InputSource;

public final class GrobidCitationParser implements CitationParser {
  private final ParserHttpClient http;
  private final URI endpoint;

  public GrobidCitationParser(URI endpoint, Duration timeout) {
    this.endpoint = endpoint;
    this.http = new ParserHttpClient(endpoint, timeout);
  }

  public String name() {
    return "grobid";
  }

  public Map<String, Object> configuration() {
    return Map.of(
        "adapterVersion",
        "grobid-tei-v1",
        "endpoint",
        endpoint.toString(),
        "consolidateCitations",
        0);
  }

  public ParsedCitation parse(ResearchDataset.ParserInput input)
      throws IOException, InterruptedException {
    String request =
        "citations="
            + URLEncoder.encode(input.rawCitation(), StandardCharsets.UTF_8)
            + "&consolidateCitations=0&includeRawCitations=1";
    var response =
        http.post(
            "api/processCitation",
            "application/x-www-form-urlencoded; charset=UTF-8",
            "application/xml",
            request);
    String body = ParserHttpClient.body(response);
    if (response.statusCode() == 204)
      return new ParsedCitation(Map.of(), List.of("GROBID не извлёк поля (HTTP 204)"), body);
    if (response.statusCode() != 200)
      throw new ParseFailure("GROBID HTTP " + response.statusCode(), body);
    return decode(body);
  }

  ParsedCitation decode(String xml) throws ParseFailure {
    try {
      var factory = DocumentBuilderFactory.newInstance();
      factory.setNamespaceAware(true);
      factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
      factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
      factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
      factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
      factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
      factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
      factory.setXIncludeAware(false);
      factory.setExpandEntityReferences(false);
      var builder = factory.newDocumentBuilder();
      builder.setErrorHandler(
          new org.xml.sax.helpers.DefaultHandler() {
            @Override
            public void fatalError(org.xml.sax.SAXParseException e)
                throws org.xml.sax.SAXException {
              throw e;
            }

            @Override
            public void error(org.xml.sax.SAXParseException e) throws org.xml.sax.SAXException {
              throw e;
            }
          });
      var document = builder.parse(new InputSource(new StringReader(xml)));
      var structs = document.getElementsByTagNameNS("*", "biblStruct");
      if (structs.getLength() != 1) throw new IllegalArgumentException("Ожидается один biblStruct");
      Element root = (Element) structs.item(0);
      Element analytic = child(root, "analytic"), monogr = child(root, "monogr");
      Element analyticTitle = child(analytic, "title"), monogrTitle = child(monogr, "title");
      boolean article = analyticTitle != null && !text(analyticTitle).isEmpty();
      Map<ReferenceField, String> fields = new EnumMap<>(ReferenceField.class);
      put(fields, ReferenceField.TITLE, text(article ? analyticTitle : monogrTitle));
      if (article) put(fields, ReferenceField.CONTAINER_TITLE, text(monogrTitle));
      String authors = authors(article ? analytic : monogr);
      if (authors.isEmpty() && article) authors = authors(monogr);
      put(fields, ReferenceField.AUTHORS, authors);
      Element imprint = child(monogr, "imprint");
      put(fields, ReferenceField.PUBLISHER, text(child(imprint, "publisher")));
      put(fields, ReferenceField.CITY, text(child(imprint, "pubPlace")));
      put(fields, ReferenceField.EDITION, text(child(monogr, "edition")));
      for (Element date : descendants(imprint, "date")) {
        if (!date.getAttribute("type").isEmpty() && !date.getAttribute("type").equals("published"))
          continue;
        String value = date.hasAttribute("when") ? date.getAttribute("when") : text(date);
        Matcher matcher =
            Pattern.compile("(?<!\\d)(?:1[5-9]\\d{2}|20\\d{2})(?!\\d)").matcher(value);
        if (matcher.find()) {
          fields.put(ReferenceField.YEAR, matcher.group());
          break;
        }
      }
      for (Element scope : descendants(imprint, "biblScope")) {
        String unit = scope.getAttribute("unit");
        if (unit.equals("volume")) put(fields, ReferenceField.VOLUME, text(scope));
        else if (unit.equals("issue")) put(fields, ReferenceField.ISSUE, text(scope));
        else if (unit.equals("page") || unit.equals("pp")) {
          String from = scope.getAttribute("from"), to = scope.getAttribute("to");
          String pages =
              from.isEmpty()
                  ? text(scope)
                  : from + (to.isEmpty() || to.equals(from) ? "" : "–" + to);
          put(fields, ReferenceField.PAGES, pages);
          if (!pages.isEmpty()) fields.put(ReferenceField.PAGE_MEANING, "Указатель страниц");
        }
      }
      for (Element id : descendants(root, "idno")) {
        switch (id.getAttribute("type").toUpperCase(Locale.ROOT)) {
          case "DOI" ->
              put(
                  fields,
                  ReferenceField.DOI,
                  text(id).replaceFirst("(?i)^https?://(?:dx\\.)?doi\\.org/", ""));
          case "ISBN", "ISBN10", "ISBN13" -> put(fields, ReferenceField.ISBN, text(id));
          case "EDN" -> put(fields, ReferenceField.EDN, text(id));
          default -> {}
        }
      }
      for (Element ptr : descendants(root, "ptr")) {
        if (!ptr.getAttribute("target").isBlank()) {
          put(fields, ReferenceField.URL, ptr.getAttribute("target"));
          break;
        }
      }
      if (monogrTitle != null && "j".equals(monogrTitle.getAttribute("level")))
        fields.put(ReferenceField.DOCUMENT_TYPE, "Журнальная статья");
      else if (article && monogrTitle != null)
        fields.put(ReferenceField.DOCUMENT_TYPE, "Материал сборника");
      else if (!text(monogrTitle).isEmpty()) fields.put(ReferenceField.DOCUMENT_TYPE, "Книга");
      return new ParsedCitation(
          fields,
          List.of(
              "Тип документа и роль страниц выведены из структуры TEI; неподдержанные поля не"
                  + " дополняются регулярными выражениями"),
          xml);
    } catch (Exception e) {
      throw new ParseFailure("Некорректный TEI GROBID: " + e.getMessage(), xml);
    }
  }

  public Map<String, Object> serverMetadata() throws IOException, InterruptedException {
    return Map.of("serverVersion", http.get("api/version").strip());
  }

  private static String authors(Element parent) {
    List<String> names = new ArrayList<>();
    if (parent == null) return "";
    for (Element author : children(parent, "author")) {
      Element person = child(author, "persName");
      if (person == null) continue;
      String surname = text(child(person, "surname"));
      List<String> initials = new ArrayList<>();
      for (Element forename : children(person, "forename")) {
        Matcher words = Pattern.compile("\\p{L}+").matcher(text(forename));
        while (words.find())
          initials.add(new String(Character.toChars(words.group().codePointAt(0))) + ".");
      }
      String name = (surname + " " + String.join(" ", initials)).strip();
      if (!name.isEmpty()) names.add(name);
    }
    return String.join(" | ", names);
  }

  private static String text(Element element) {
    return element == null ? "" : element.getTextContent().replaceAll("\\s+", " ").strip();
  }

  private static Element child(Element parent, String name) {
    return children(parent, name).stream().findFirst().orElse(null);
  }

  private static List<Element> children(Element parent, String name) {
    List<Element> result = new ArrayList<>();
    if (parent != null)
      for (Node node = parent.getFirstChild(); node != null; node = node.getNextSibling()) {
        if (node instanceof Element e
            && name.equals(e.getLocalName() == null ? e.getTagName() : e.getLocalName()))
          result.add(e);
      }
    return result;
  }

  private static List<Element> descendants(Element parent, String name) {
    List<Element> result = new ArrayList<>();
    if (parent != null) {
      NodeList nodes = parent.getElementsByTagNameNS("*", name);
      for (int i = 0; i < nodes.getLength(); i++) result.add((Element) nodes.item(i));
    }
    return result;
  }

  private static void put(Map<ReferenceField, String> fields, ReferenceField key, String value) {
    if (!value.isBlank()) fields.put(key, value);
  }
}
