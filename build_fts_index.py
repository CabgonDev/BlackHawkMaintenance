# build_fts_index.py
# Genera automáticamente los índices FTS para IADS y Sikorsky

import fitz, sqlite3, os
from pathlib import Path

# --- Configuración principal ---
PAQUETES = {
    "iads": {
        "pdf_dir": Path("app/src/main/assets/iads/manuals"),
        "out_db": Path("app/src/main/assets/iads/index/blackhawk_iads_fts.db"),
        "asset_prefix": "iads/manuals"
    },
    "sikorsky": {
        "pdf_dir": Path("app/src/main/assets/sikorsky/manuals"),
        "out_db": Path("app/src/main/assets/sikorsky/index/blackhawk_sikorsky_fts.db"),
        "asset_prefix": "sikorsky/manuals"
    }
}

def extract_pages_text(pdf_path: Path):
    doc = fitz.open(pdf_path)
    for page_idx in range(doc.page_count):
        page = doc.load_page(page_idx)
        text = page.get_text("text") or ""
        yield (page_idx + 1, text.strip())
    doc.close()

def build_index(pdf_dir: Path, asset_prefix: str, out_db: Path):
    out_db.parent.mkdir(parents=True, exist_ok=True)
    if out_db.exists():
        out_db.unlink()

    con = sqlite3.connect(out_db)
    cur = con.cursor()
    cur.execute("""
        CREATE VIRTUAL TABLE pages_fts USING fts5(
          text,
          page UNINDEXED,
          manual UNINDEXED,
          tokenize = 'unicode61'
        );
    """)

    pdfs = sorted([p for p in pdf_dir.glob("*.pdf")])
    total_rows = 0

    for pdf in pdfs:
        print(f"[INFO] Indexando: {pdf.name}")
        for page_num, text in extract_pages_text(pdf):
            if not text.strip():
                continue
            clean = " ".join(text.split())
            cur.execute(
                "INSERT INTO pages_fts(text, page, manual) VALUES (?, ?, ?)",
                (clean, page_num, f"{asset_prefix}/{pdf.name}")
            )
            total_rows += 1

        con.commit()

    cur.execute("INSERT INTO pages_fts(pages_fts) VALUES('optimize');")
    con.commit()
    con.close()
    print(f"[OK] {out_db.name}: {total_rows} páginas indexadas.\n")

def main():
    for key, cfg in PAQUETES.items():
        if not cfg["pdf_dir"].exists():
            print(f"[WARN] No se encontró carpeta: {cfg['pdf_dir']}")
            continue
        build_index(cfg["pdf_dir"], cfg["asset_prefix"], cfg["out_db"])
    print("[DONE] Índices generados correctamente para todos los paquetes.")

if __name__ == "__main__":
    main()
