package ru.ssau.bibliography.research;

/** Fields extracted from the original citation; external formatting context is separate. */
public enum ReferenceField {
  AUTHORS("Авторы"),
  TITLE("Заглавие"),
  CONTAINER_TITLE("Содержащее издание"),
  YEAR("Год"),
  VOLUME("Том"),
  ISSUE("Номер или выпуск"),
  PAGES("Страницы или объём"),
  PAGE_MEANING("Значение страниц"),
  PUBLISHER("Издатель"),
  CITY("Место издания"),
  EDITION("Сведения об издании"),
  ISBN("ISBN"),
  URL("URL из исходной строки"),
  ACCESS_DATE("Дата обращения из строки"),
  DOI("DOI из исходной строки"),
  EDN("EDN"),
  DOCUMENT_TYPE("Вид документа");

  private final String header;

  ReferenceField(String header) {
    this.header = header;
  }

  public String header() {
    return header;
  }

  public static ReferenceField fromAnnotationName(String name) {
    for (ReferenceField field : values()) {
      if (field.header.equals(name)
          || (field == YEAR && name.equals("Год публикации"))
          || (field == DOI && name.equals("DOI"))) return field;
    }
    throw new IllegalArgumentException("Неизвестное неоднозначное поле: " + name);
  }
}
