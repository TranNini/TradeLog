-- price_cents: the price in cents, so sums have no rounding errors
-- place: where it was sold or bought (eBay, Vinted, flea market …)
-- date: ISO date, e.g. 2026-10-12
-- image: file name in the images folder, or NULL
CREATE TABLE IF NOT EXISTS items (
    id          INTEGER PRIMARY KEY AUTOINCREMENT,
    type        TEXT    NOT NULL CHECK (type IN ('SOLD', 'BOUGHT')),
    name        TEXT    NOT NULL,
    category    TEXT,
    price_cents INTEGER NOT NULL,
    place       TEXT,
    date        TEXT    NOT NULL,
    notes       TEXT,
    image       TEXT
);
