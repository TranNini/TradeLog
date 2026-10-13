-- price_cents: the price in cents, so sums have no rounding errors
-- type: SOLD, BOUGHT or FOR_SALE (listed, not sold yet; price = asking price)
-- place: where it was sold, bought or listed (eBay, Vinted, flea market …)
-- date: month and year, e.g. 2026-10, or NULL when none was entered
--   (older databases had NOT NULL here and no FOR_SALE; SchemaMigration updates them)
-- image: file name in the images folder, or NULL
CREATE TABLE IF NOT EXISTS items (
    id          INTEGER PRIMARY KEY AUTOINCREMENT,
    type        TEXT    NOT NULL CHECK (type IN ('SOLD', 'BOUGHT', 'FOR_SALE')),
    name        TEXT    NOT NULL,
    category    TEXT,
    price_cents INTEGER NOT NULL,
    place       TEXT,
    date        TEXT,
    notes       TEXT,
    image       TEXT
);
