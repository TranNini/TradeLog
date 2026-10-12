# TradeLog

A small app to keep track of everything you sell and buy: name, price, where, date, category, notes
and a picture. It adds up what you sold and bought, and has a search bar and filters for type and category.

## Run

    mvn spring-boot:run

or run `tradelog.TradeLogApp` in IntelliJ, then open http://localhost:8081.

## Data

- `tradelog.db`: SQLite database with all items (table `items`, see `src/main/resources/schema.sql`)
- `images/`: the pictures, one file per item

Both are created in the folder the app is started from and are git-ignored. To back up your data, copy both.

## Project structure

| File | What it does |
|---|---|
| `TradeLogApp.java` | starts the app |
| `ItemController.java` | REST API under `/api`: list with filters and totals, add / edit / delete, pictures |
| `ItemRepository.java` | reads and writes the `items` table |
| `ImageStore.java` | saves and deletes the picture files |
| `static/index.html`, `app.js`, `style.css` | the UI |
