// Filtering, totals and saving happen in Java (see ItemController);
// this file only shows the screens and talks to /api.

const money = new Intl.NumberFormat(undefined, {style: "currency", currency: "EUR"});

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

function plural(count, word) {
    return `${count} ${word}${count === 1 ? "" : "s"}`;
}

function setThumb(node, imageUrl) {
    node.style.backgroundImage = imageUrl ? `url("${imageUrl}")` : "";
    node.textContent = imageUrl ? "" : "📦";
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
    form.elements.price.value = item?.price ?? "";
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
    const body = {
        type: fields.type.value,
        name: fields.name.value,
        price: fields.price.value === "" ? null : Number(fields.price.value),
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
            const data = new FormData();
            data.append("file", pendingPicture);
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
