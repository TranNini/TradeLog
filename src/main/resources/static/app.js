// Filtering, totals and saving happen in Java (see ItemController);
// this file only shows the screens and talks to /api.

const money = new Intl.NumberFormat(undefined, {style: "currency", currency: "EUR"});
// Phone photos are often several MB; they are made at most this many pixels wide and high before
// uploading, so they upload quickly over mobile data and take less space on the NAS
const MAX_PICTURE_PIXELS = 1600;
const SUPPORTED_PICTURES = ["image/jpeg", "image/png", "image/gif", "image/webp"];

let filter = {type: "", category: "", q: ""};
let editing = null; // the item open in the editor, or null when adding a new one
let pendingPicture = null; // picture chosen in the editor, uploaded on save
let removePicture = false;

// ---------- helpers ----------

const $ = (id) => document.getElementById(id);

function el(tag, props = {}, ...children) {
    const node = document.createElement(tag);
    Object.assign(node, props);
    node.append(...children);
    return node;
}

async function api(method, url, body) {
    const isForm = body instanceof FormData;
    const response = await fetch(url, {
        method,
        headers: body && !isForm ? {"Content-Type": "application/json"} : {},
        body: isForm ? body : body ? JSON.stringify(body) : undefined,
    });
    if (response.status === 401) {
        // this device hasn't entered the access code yet
        location.href = "/login.html";
        throw new Error("Please enter the access code.");
    }
    const data = response.status === 204 ? null : await response.json().catch(() => null);
    if (!response.ok) {
        throw new Error(data?.error || "Something went wrong.");
    }
    return data;
}

function formatDate(isoDate) {
    return new Date(isoDate + "T00:00").toLocaleDateString(undefined, {
        day: "numeric", month: "short", year: "numeric",
    });
}

// Prices are typed into a text field, because number fields on some phones (Safari with a German keyboard)
// throw away "1,50". Accepts 1,50 / 1.50 / 1 / € 1,50; returns null for anything else.
function parsePrice(text) {
    const cleaned = text.replace(/[€\s]/g, "").replace(",", ".");
    return /^\d+(\.\d{1,2})?$/.test(cleaned) ? Number(cleaned) : null;
}

// 1.5 -> "1,50" or "1.50", depending on the device's language
function priceText(price) {
    return Number(price).toLocaleString(undefined, {
        minimumFractionDigits: 2, maximumFractionDigits: 2, useGrouping: false,
    });
}

function plural(count, word) {
    return `${count} ${word}${count === 1 ? "" : "s"}`;
}

function setThumb(node, imageUrl) {
    node.style.backgroundImage = imageUrl ? `url("${imageUrl}")` : "";
    node.textContent = imageUrl ? "" : "📦";
}

// A smaller JPEG copy of the picture, or the picture itself when that isn't smaller (or the browser
// can't read it; then the server says whether the type is OK)
async function shrinkPicture(file) {
    if (file.type === "image/gif") return file; // would lose the animation
    try {
        const bitmap = await createImageBitmap(file);
        const scale = Math.min(1, MAX_PICTURE_PIXELS / Math.max(bitmap.width, bitmap.height));
        const canvas = el("canvas", {
            width: Math.round(bitmap.width * scale),
            height: Math.round(bitmap.height * scale),
        });
        const context = canvas.getContext("2d");
        context.fillStyle = "#ffffff"; // JPEG has no transparency
        context.fillRect(0, 0, canvas.width, canvas.height);
        context.drawImage(bitmap, 0, 0, canvas.width, canvas.height);
        const jpeg = await new Promise((resolve) => canvas.toBlob(resolve, "image/jpeg", 0.85));
        if (jpeg && (jpeg.size < file.size || !SUPPORTED_PICTURES.includes(file.type))) return jpeg;
    } catch (error) {
        // e.g. a HEIC photo on a browser that can't show it
    }
    return file;
}

// ---------- list ----------

async function loadItems() {
    const params = new URLSearchParams();
    if (filter.type) params.set("type", filter.type);
    if (filter.category) params.set("category", filter.category);
    if (filter.q) params.set("q", filter.q);
    const {items, totals} = await api("GET", "/api/items?" + params);
    showTotals(totals);
    showItems(items);
}

function showTotals(totals) {
    $("sold-sum").textContent = money.format(totals.sold);
    $("sold-count").textContent = plural(totals.soldCount, "item");
    $("bought-sum").textContent = money.format(totals.bought);
    $("bought-count").textContent = plural(totals.boughtCount, "item");
    $("balance").textContent = money.format(totals.balance);

    const parts = [];
    if (filter.category) parts.push(`in “${filter.category}”`);
    if (filter.q) parts.push(`matching “${filter.q}”`);
    $("scope").hidden = parts.length === 0;
    $("scope").textContent = "Totals for items " + parts.join(" ");
}

function showItems(items) {
    const list = $("list");
    list.replaceChildren(...items.map(itemRow));

    const filtered = filter.type || filter.category || filter.q;
    $("empty").hidden = items.length > 0;
    $("empty").textContent = filtered
        ? "No items match your filters."
        : "No items yet. Click “Add item” to log your first sale or purchase.";
}

function itemRow(item) {
    const thumb = el("div", {className: "thumb"});
    setThumb(thumb, item.imageUrl);
    if (item.imageUrl) {
        thumb.title = "Show picture";
        thumb.style.cursor = "zoom-in";
        thumb.addEventListener("click", (event) => {
            event.stopPropagation();
            $("viewer-image").src = item.imageUrl;
            $("viewer").showModal();
        });
    }

    const meta = [item.category, item.place, formatDate(item.date)].filter(Boolean).join(" · ");
    const row = el("li", {className: "item"},
        thumb,
        el("div", {className: "info"},
            el("div", {className: "name", textContent: item.name}),
            el("div", {className: "meta", textContent: meta})),
        el("div", {className: "price"},
            el("span", {className: "badge " + item.type, textContent: item.type === "SOLD" ? "Sold" : "Bought"}),
            money.format(item.price)));
    row.addEventListener("click", () => openEditor(item));
    return row;
}

// Fills the category filter and the suggestions in the editor
async function loadOptions() {
    const {categories, places} = await api("GET", "/api/options");

    if (filter.category && !categories.some((c) => c.toLowerCase() === filter.category.toLowerCase())) {
        filter.category = ""; // its last item was changed or deleted
    }
    $("category-filter").replaceChildren(
        el("option", {value: "", textContent: "All categories"}),
        ...categories.map((c) => el("option", {value: c, textContent: c})));
    $("category-filter").value = filter.category;

    $("categories").replaceChildren(...categories.map((c) => el("option", {value: c})));
    $("places").replaceChildren(...places.map((p) => el("option", {value: p})));
}

async function refresh() {
    try {
        await loadOptions();
        await loadItems();
    } catch (error) {
        $("empty").hidden = false;
        $("empty").textContent = error.message;
    }
}

// ---------- editor ----------

function openEditor(item) {
    editing = item;
    pendingPicture = null;
    removePicture = false;

    const form = $("item-form");
    form.reset();
    $("editor-title").textContent = item ? "Edit item" : "Add item";
    $("delete").hidden = !item;
    $("form-error").hidden = true;

    form.elements.type.value = item?.type ?? (filter.type || "SOLD");
    form.elements.name.value = item?.name ?? "";
    form.elements.price.value = item ? priceText(item.price) : "";
    form.elements.date.value = item?.date ?? new Date().toLocaleDateString("en-CA"); // en-CA = YYYY-MM-DD
    form.elements.place.value = item?.place ?? "";
    form.elements.category.value = item?.category ?? (filter.category || "");
    form.elements.notes.value = item?.notes ?? "";
    updatePlaceLabel();
    showPreview(item?.imageUrl);

    $("editor").showModal();
}

function updatePlaceLabel() {
    const sold = $("item-form").elements.type.value === "SOLD";
    $("place-label").textContent = sold ? "Where sold" : "Where bought";
}

function showPreview(url) {
    setThumb($("preview"), url);
    $("remove-picture").hidden = !url;
}

function showFormError(message) {
    $("form-error").textContent = message;
    $("form-error").hidden = false;
}

async function save(event) {
    event.preventDefault();
    const fields = $("item-form").elements;
    const price = parsePrice(fields.price.value);
    if (price === null) {
        showFormError("Please enter a price like 1,50 or 12.");
        return;
    }
    const body = {
        type: fields.type.value,
        name: fields.name.value,
        price,
        date: fields.date.value || null,
        place: fields.place.value,
        category: fields.category.value,
        notes: fields.notes.value,
    };
    try {
        const item = editing
            ? await api("PUT", `/api/items/${editing.id}`, body)
            : await api("POST", "/api/items", body);
        editing = item; // if the picture upload fails, saving again updates this item instead of adding another

        if (pendingPicture) {
            const picture = await shrinkPicture(pendingPicture);
            const data = new FormData();
            data.append("file", picture, picture === pendingPicture ? pendingPicture.name : "picture.jpg");
            await api("POST", `/api/items/${item.id}/image`, data);
        } else if (removePicture && item.imageUrl) {
            await api("DELETE", `/api/items/${item.id}/image`);
        }
        $("editor").close();
        await refresh();
    } catch (error) {
        showFormError(error.message);
    }
}

async function deleteItem() {
    if (!confirm(`Delete “${editing.name}”?`)) return;
    try {
        await api("DELETE", `/api/items/${editing.id}`);
        $("editor").close();
        await refresh();
    } catch (error) {
        showFormError(error.message);
    }
}

// ---------- events ----------

$("add").addEventListener("click", () => openEditor(null));

$("type-filter").addEventListener("click", (event) => {
    const button = event.target.closest("button");
    if (!button) return;
    filter.type = button.dataset.type;
    for (const b of $("type-filter").children) b.classList.toggle("active", b === button);
    loadItems().catch((error) => alert(error.message));
});

$("category-filter").addEventListener("change", (event) => {
    filter.category = event.target.value;
    loadItems().catch((error) => alert(error.message));
});

let searchTimer;
$("search").addEventListener("input", (event) => {
    clearTimeout(searchTimer);
    searchTimer = setTimeout(() => {
        filter.q = event.target.value.trim();
        loadItems().catch((error) => alert(error.message));
    }, 200);
});

$("item-form").addEventListener("submit", save);
$("item-form").addEventListener("change", (event) => {
    if (event.target.name === "type") updatePlaceLabel();
});
$("cancel").addEventListener("click", () => $("editor").close());
$("delete").addEventListener("click", deleteItem);

$("picture").addEventListener("change", (event) => {
    const file = event.target.files[0];
    if (!file) return;
    pendingPicture = file;
    removePicture = false;
    showPreview(URL.createObjectURL(file));
});

$("remove-picture").addEventListener("click", () => {
    pendingPicture = null;
    removePicture = true;
    $("picture").value = "";
    showPreview(null);
});

$("viewer").addEventListener("click", () => $("viewer").close());

refresh();
