# TradeLog

A small app to keep track of everything you sell and buy: name, price, where, date, category, notes
and a picture. It adds up what you sold and bought, and has a search bar and filters for type and category.

## Use it

The app runs on the NAS: open http://192.168.178.130:7897 on any device in the home network.
Each device asks for the access code once. On the iPhone, tap Share → "Add to Home Screen" to get an app icon.

## Run locally

    mvn spring-boot:run

or run `tradelog.TradeLogApp` in IntelliJ, then open http://localhost:8081. This Mac needs no access code.

## Data

- `tradelog.db`: SQLite database with all items (table `items`, see `src/main/resources/schema.sql`)
- `images/`: the pictures, one file per item
- `access-code.txt`: the code other devices enter once

Locally they are created in the folder the app is started from (git-ignored). On the NAS they are in
`/share/CACHEDEV1_DATA/homes/jank/tradelog/`. To back up your data, copy that folder.

## Project structure

| File | What it does |
|---|---|
| `TradeLogApp.java` | starts the app |
| `ItemController.java` | REST API under `/api`: list with filters and totals, add / edit / delete, pictures |
| `ItemRepository.java` | reads and writes the `items` table |
| `ImageStore.java` | saves and deletes the picture files |
| `AccessCode.java`, `AccessFilter.java`, `LoginController.java` | the access code login |
| `Dockerfile` | the image for the NAS (see CLAUDE.md for releasing) |
| `static/index.html`, `app.js`, `style.css` | the UI |
