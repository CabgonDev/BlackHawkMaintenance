Coloca aquí tus PDFs e índices FTS:

- IADS:
  - PDFs:    app/src/main/assets/iads/manuals/*.pdf
  - Índice:  app/src/main/assets/iads/index/blackhawk_iads_fts.db
- Sikorsky:
  - PDFs:    app/src/main/assets/sikorsky/manuals/*.pdf
  - Índice:  app/src/main/assets/sikorsky/index/blackhawk_sikorsky_fts.db

Esquema esperado del índice (SQLite FTS5):
  CREATE VIRTUAL TABLE pages_fts USING fts5(
    text,
    page UNINDEXED,
    manual UNINDEXED,
    tokenize = 'porter'
  );

Cada fila corresponde a una página de un manual:
  INSERT INTO pages_fts(text, page, manual) VALUES (?, ?, 'iads/manuals/TM_1-1520-L-23-1.pdf');